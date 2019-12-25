package com.tianyalei.async.group;


import com.tianyalei.async.callback.DefaultCallback;
import com.tianyalei.async.callback.ICallback;
import com.tianyalei.async.callback.IWorker;
import com.tianyalei.async.executor.timer.SystemClock;
import com.tianyalei.async.worker.DependWrapper;
import com.tianyalei.async.worker.ResultState;
import com.tianyalei.async.worker.WorkResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对每个worker及callback进行包装，一对一
 *
 * @author wuweifeng wrote on 2019-11-19.
 */
public class WorkerWrapper<T, V> {
    /**
     * worker将来要处理的param
     */
    private T param;
    private IWorker<T, V> worker;
    private ICallback<T, V> callback;
    /**
     * 在自己后面的wrapper，如果没有，自己就是末尾；如果有一个，就是串行；如果有多个，有几个就需要开几个线程</p>
     * -------2
     * 1
     * -------3
     * 如1后面有2、3
     */
    private List<WorkerWrapper<?, ?>> nextWrappers;
    /**
     * 依赖的wrappers，必须依赖的全部完成后，才能执行自己
     * 1
     * -------3
     * 2
     * 1、2执行完毕后才能执行3
     */
    private List<DependWrapper> dependWrappers;
    /**
     * 标记该事件是否已经被处理过了，譬如已经超时返回false了，后续rpc又收到返回值了，则不再二次回调
     * 经试验,volatile并不能保证"同一毫秒"内,多线程对该值的修改和拉取
     * <p>
     * 1-finish, 2-error, 3-working
     */
    private AtomicInteger state = new AtomicInteger(0);
    /**
     * 也是个钩子变量，用来存临时的结果
     */
    private volatile WorkResult<V> workResult;

    private static final int FINISH = 1;
    private static final int ERROR = 2;
    private static final int WORKING = 3;
    private static final int INIT = 0;

    public WorkerWrapper(IWorker<T, V> worker, T param, ICallback<T, V> callback) {
        if (worker == null) {
            throw new NullPointerException("async.worker is null");
        }
        this.worker = worker;
        this.param = param;
        //允许不设置回调
        if (callback == null) {
            callback = new DefaultCallback<>();
        }
        this.callback = callback;
    }

    /**
     * 开始工作
     * fatherWrapper代表这次work是由哪个上游wrapper发起的
     */
    private void work(ThreadPoolExecutor poolExecutor, WorkerWrapper fromWrapper, long remainTime) {
        long now = SystemClock.now();
        //总的已经超时了，就快速失败，进行下一个
        if (remainTime <= 0) {
            fastFail(INIT, null);
            beginNext(poolExecutor, now, remainTime);
            return;
        }
        //如果自己已经执行过了。（可能有多个依赖，其中的一个依赖已经执行完了，并且自己也执行完了。当另一个依赖过来时，就不重复处理了）
        if (getState() != INIT) {
            beginNext(poolExecutor, now, remainTime);
            return;
        }

        //如果没有任何依赖，说明自己就是第一批要执行的
        if (dependWrappers == null || dependWrappers.size() == 0) {
            fire(poolExecutor, remainTime);
            beginNext(poolExecutor, now, remainTime);
            return;
        }

        //如果有前方依赖，存在两种情况
        // 一种是前面只有一个wrapper。即 A  ->  B
        //一种是前面有多个wrapper。A C D ->   B。需要A、C、D都完成了才能轮到B。但是无论是A执行完，还是C执行完，都会去唤醒B。
        //所以需要B来做判断，必须A、C、D都完成，自己才能执行
        if (dependWrappers.size() == 1) {
            doDependsOneJob(poolExecutor, fromWrapper, remainTime);
            beginNext(poolExecutor, now, remainTime);
            return;
        }

        doDependsJobs(poolExecutor, dependWrappers, fromWrapper, now, remainTime);
    }


    public void work(ThreadPoolExecutor poolExecutor, long remainTime) {
        work(poolExecutor, null, remainTime);
    }

    /**
     * 总控制台超时，停止所有任务
     */
    public void stopNow() {
        if (getState() == INIT || getState() == WORKING) {
            fastFail(getState(), null);
        }
    }

    /**
     * 进行下一个任务
     */
    private void beginNext(ThreadPoolExecutor poolExecutor, long now, long remainTime) {
//        System.out.println("now is " + SystemClock.now() + " and thread count : " + getThreadCount());
        //花费的时间
        long costTime = SystemClock.now() - now;
        if (nextWrappers == null) {
            return;
        }
        if (nextWrappers.size() == 1) {
            nextWrappers.get(0).work(poolExecutor, WorkerWrapper.this, remainTime - costTime);
            return;
        }
        CompletableFuture[] futures = new CompletableFuture[nextWrappers.size()];
        for (int i = 0; i < nextWrappers.size(); i++) {
            int finalI = i;
            futures[i] = CompletableFuture.runAsync(() -> nextWrappers.get(finalI)
                    .work(poolExecutor, WorkerWrapper.this, remainTime - costTime), poolExecutor);
        }
        try {
            CompletableFuture.allOf(futures).get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    private void doDependsOneJob(ThreadPoolExecutor poolExecutor, WorkerWrapper dependWrapper, long remainTime) {
        if (ResultState.TIMEOUT == dependWrapper.getWorkResult().getResultState()) {
            workResult = defaultResult();
            fastFail(INIT, null);
        } else if (ResultState.EXCEPTION == dependWrapper.getWorkResult().getResultState()) {
            workResult = defaultExResult(dependWrapper.getWorkResult().getEx());
            fastFail(INIT, null);
        } else {
            //前面任务正常完毕了，该自己了
            fire(poolExecutor, remainTime);
        }
    }

    private void doDependsJobs(ThreadPoolExecutor poolExecutor, List<DependWrapper> dependWrappers, WorkerWrapper fromWrapper, long now, long remainTime) {
        boolean nowDependIsMust = false;
        //创建必须完成的上游wrapper集合
        Set<DependWrapper> mustWrapper = new HashSet<>();
        for (DependWrapper dependWrapper : dependWrappers) {
            if (dependWrapper.isMust()) {
                mustWrapper.add(dependWrapper);
            }
            if (dependWrapper.getDependWrapper().equals(fromWrapper)) {
                nowDependIsMust = dependWrapper.isMust();
            }
        }

        //如果全部是不必须的条件，那么只要到了这里，就执行自己。
        if (mustWrapper.size() == 0) {
            if (ResultState.TIMEOUT == fromWrapper.getWorkResult().getResultState()) {
                fastFail(INIT, null);
            } else {
                fire(poolExecutor, remainTime);
            }
            beginNext(poolExecutor, now, remainTime);
            return;
        }

        //如果存在需要必须完成的，且fromWrapper不是必须的，就什么也不干
        if (!nowDependIsMust) {
            return;
        }

        //如果fromWrapper是必须的
        boolean existNoFinish = false;
        boolean hasError = false;
        //先判断前面必须要执行的依赖任务的执行结果，如果有任何一个失败，那就不用走action了，直接给自己设置为失败，进行下一步就是了
        for (DependWrapper dependWrapper : mustWrapper) {
            WorkerWrapper workerWrapper = dependWrapper.getDependWrapper();
            WorkResult tempWorkResult = workerWrapper.getWorkResult();
            //为null或者isWorking，说明它依赖的某个任务还没执行到或没执行完
            if (tempWorkResult == null || workerWrapper.getState() == WORKING) {
                existNoFinish = true;
                break;
            }
            if (ResultState.TIMEOUT == tempWorkResult.getResultState()) {
                workResult = defaultResult();
                hasError = true;
                break;
            }
            if (ResultState.EXCEPTION == tempWorkResult.getResultState()) {
                workResult = defaultExResult(workerWrapper.getWorkResult().getEx());
                hasError = true;
                break;
            }

        }
        //只要有失败的
        if (hasError) {
            fastFail(INIT, null);
            beginNext(poolExecutor, now, remainTime);
            return;
        }

        //如果上游都没有失败，分为两种情况，一种是都finish了，一种是有的在working
        //都finish的话
        if (!existNoFinish) {
            //上游都finish了，进行自己
            fire(poolExecutor, remainTime);
            beginNext(poolExecutor, now, remainTime);
            return;
        }
    }

    /**
     * 执行自己的job.具体的执行是在另一个线程里,但判断阻塞超时是在work线程
     */
    private void fire(ThreadPoolExecutor poolExecutor, long workerTimeOut) {
        //阻塞取结果
        workResult = workerDoJob();

//        completableFuture = CompletableFuture.supplyAsync(this::workerDoJob,
//                poolExecutor);

//        try {
//            //阻塞取结果
//            workResult = completableFuture.get(workerTimeOut, TimeUnit.MILLISECONDS);
//        } catch (InterruptedException | ExecutionException | TimeoutException e) {
////            e.printStackTrace();
//            System.out.println("exception " + Thread.currentThread().getName());
//            //超时了.如果已经处理过了
//            if (getState() == FINISH || getState() == ERROR) {
//                return;
//            }
//            if (fastFail(WORKING, null)) {
//                completableFuture.complete(workResult);
//            }
//        }
    }

    /**
     * 快速失败
     */
    private boolean fastFail(int expect, Exception e) {
        System.out.println("fastFail:" + Thread.currentThread().getName() + " time " + System.currentTimeMillis());

        //试图将它从expect状态,改成Error
        if (!compareAndSetState(expect, ERROR)) {
            System.out.println("compareAndSetState----------fail");
            return false;
        }

        if (workResult == null) {
            if (e == null) {
                workResult = defaultResult();
            } else {
                workResult = defaultExResult(e);
            }
        }

        callback.result(false, getParam(), workResult);
        return true;
    }

    /**
     * 具体的单个worker执行任务
     */
    private WorkResult<V> workerDoJob() {
        //避免重复执行
        if (workResult != null) {
            return workResult;
        }
        try {
            //如果已经不是init状态了
            if (!compareAndSetState(INIT, WORKING)) {
                return workResult;
            }

            callback.begin();

            //执行耗时操作
            V resultValue = worker.action(getParam());

            WorkResult<V> tempResult = new WorkResult<>(resultValue, ResultState.SUCCESS);

            //如果状态不是在working,说明别的地方已经修改了
            if (!compareAndSetState(WORKING, FINISH)) {
                return workResult;
            }
            //回调成功
            callback.result(true, getParam(), tempResult);
            workResult = tempResult;

            return workResult;
        } catch (Exception e) {
            //避免重复回调
            if (workResult != null) {
                return workResult;
            }
            fastFail(WORKING, e);
            return workResult;
        }
    }


    public WorkerWrapper addNext(WorkerWrapper<?, ?>... nextWrappers) {
        if (nextWrappers == null) {
            return this;
        }
        for (WorkerWrapper<?, ?> workerWrapper : nextWrappers) {
            addNext(workerWrapper);
        }
        return this;
    }

    public WorkerWrapper addNext(IWorker<T, V> worker, T param, ICallback<T, V> callback) {
        WorkerWrapper<T, V> workerWrapper = new WorkerWrapper<>(worker, param, callback);
        return addNext(workerWrapper);
    }

    public WorkerWrapper addNext(WorkerWrapper<?, ?> workerWrapper) {
        if (nextWrappers == null) {
            nextWrappers = new ArrayList<>();
        }
        nextWrappers.add(workerWrapper);
        workerWrapper.addDepend(this);
        return this;
    }

    /**
     * 设置这几个依赖的wrapper不是must执行完毕才能执行自己
     */
    public void setDependNotMust(WorkerWrapper<?, ?>... workerWrapper) {
        if (dependWrappers == null) {
            return;
        }
        if (workerWrapper == null) {
            return;
        }
        for (DependWrapper dependWrapper : dependWrappers) {
            for (WorkerWrapper wrapper : workerWrapper) {
                if (dependWrapper.getDependWrapper().equals(wrapper)) {
                    dependWrapper.setMust(false);
                }
            }
        }
    }


    private void addDepend(WorkerWrapper<?, ?> workerWrapper) {
        this.addDepend(workerWrapper, true);
    }

    private void addDepend(WorkerWrapper<?, ?> workerWrapper, boolean must) {
        if (dependWrappers == null) {
            dependWrappers = new ArrayList<>();
        }
        dependWrappers.add(new DependWrapper(workerWrapper, must));
    }

    private WorkResult<V> defaultResult() {
        return new WorkResult<>(getWorker().defaultValue(), ResultState.TIMEOUT);
    }

    private WorkResult<V> defaultExResult(Exception ex) {
        return new WorkResult<>(getWorker().defaultValue(), ResultState.EXCEPTION, ex);
    }

    private WorkResult<V> getNoneNullWorkResult() {
        if (workResult == null) {
            return defaultResult();
        }
        return workResult;
    }

    public T getParam() {
        return param;
    }

    public IWorker<T, V> getWorker() {
        return worker;
    }

    public ICallback<T, V> getCallback() {
        return callback;
    }

    public List<WorkerWrapper<?, ?>> getNextWrappers() {
        return nextWrappers;
    }

    public void setNextWrappers(List<WorkerWrapper<?, ?>> nextWrappers) {
        this.nextWrappers = nextWrappers;
    }

    public List<DependWrapper> getDependWrappers() {
        return dependWrappers;
    }

    public void setDependWrappers(List<DependWrapper> dependWrappers) {
        this.dependWrappers = dependWrappers;
    }

    public int getState() {
        return state.get();
    }

    public boolean compareAndSetState(int expect, int update) {
        return this.state.compareAndSet(expect, update);
    }

    public WorkResult<V> getWorkResult() {
        return workResult;
    }

    public void setWorkResult(WorkResult<V> workResult) {
        this.workResult = workResult;
    }

}
