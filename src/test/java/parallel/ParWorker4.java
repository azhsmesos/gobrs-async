package parallel;


import com.jd.platform.gobrs.async.callback.ICallback;
import com.jd.platform.gobrs.async.callback.IWorker;
import com.jd.platform.gobrs.async.executor.timer.SystemClock;
import com.jd.platform.gobrs.async.worker.TaskResult;
import com.jd.platform.gobrs.async.wrapper.TaskWrapper;

import java.util.Map;

/**
 * @author sizegang wrote on 2019-11-20.
 */
public class ParWorker4 implements IWorker<String, String>, ICallback<String, String> {

    @Override
    public String action(String object, Map<String, TaskWrapper> allWrappers) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return "result = " + SystemClock.now() + "---param = " + object + " from 4";
    }


    @Override
    public String defaultValue() {
        return "worker4--default";
    }

    @Override
    public void begin() {
        //System.out.println(Thread.currentThread().getName() + "- start --" + System.currentTimeMillis());
    }

    @Override
    public void result(boolean success, String param, TaskResult<String> workResult) {
        if (success) {
            System.out.println("callback worker4 success--" + SystemClock.now() + "----" + workResult.getResult()
                    + "-threadName:" +Thread.currentThread().getName());
        } else {
            System.err.println("callback worker4 failure--" + SystemClock.now() + "----"  + workResult.getResult()
                    + "-threadName:" +Thread.currentThread().getName());
        }
    }

}
