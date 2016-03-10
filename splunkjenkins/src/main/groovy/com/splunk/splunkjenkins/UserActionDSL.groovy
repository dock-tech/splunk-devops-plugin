package com.splunk.splunkjenkins

import com.splunk.logging.utils.LogHelper
import hudson.EnvVars
import hudson.model.AbstractBuild
import hudson.model.TaskListener
import hudson.tasks.junit.TestResult
import hudson.tasks.junit.TestResultAction
import hudson.util.spring.ClosureScript
import jenkins.model.Jenkins
import java.util.HashMap
import java.util.Map
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer
import static com.splunk.logging.Constants.BUILD_ID;
import static com.splunk.logging.Constants.METADATA;

public class UserActionDSL {

    public void perform(AbstractBuild build) {
        try {
            EnvVars enVars = build.getEnvironment(TaskListener.NULL);
            // parse junit test result, right now we only care junit test result,
            // will ignore aggregated test results (hudson.tasks.test.AggregatedTestResultAction)
            TestResultAction resultAction = build.getAction(TestResultAction.class);
            TestResult testResult = resultAction?.result;
            if (SplunkLogService.script != null) {
                RunDelegate delegate = new RunDelegate(build, enVars, testResult);
                CompilerConfiguration cc = new CompilerConfiguration();
                cc.scriptBaseClass = ClosureScript.class.name;
                ImportCustomizer ic = new ImportCustomizer()
                ic.addStaticStars(LogHelper.class.name)
                cc.addCompilationCustomizers(ic)
                ClosureScript dslScript = (ClosureScript) new GroovyShell(Jenkins.instance.pluginManager.uberClassLoader, new Binding(), cc).parse(SplunkLogService.script)
                dslScript.setDelegate(delegate);
            } else {//user not provide post action, use default
                String url = build.getUrl();
                Map event=new HashMap();
                event.put("job_result", url + "_" + build.getTimestamp());
                event.put(BUILD_ID, url);
                event.put(METADATA, enVars);
                if (testResult != null) {
                    event.put("suites", testResult.getSuites());
                    event.put("duration", testResult.getDuration());
                    event.put("total_count", testResult.getTotalCount());
                    event.put("pass_count", testResult.getPassCount());
                    event.put("fail_count", testResult.getFailCount());
                    event.put("skip_count", testResult.getSkipCount())
                    //TODO send raw xml files?
                } else {
                    event.put("junit_result", "unknown");
                }
                SplunkLogService.send(event);
            }
            //flush the cache to send remain events
            SplunkLogService.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

public class RunDelegate {
    AbstractBuild build;
    Map env;
    TestResult testResult;

    RunDelegate(AbstractBuild build, EnvVars enVars, TestResult testResult) {
        this.build = build;
        if (enVars != null) {
            this.env = enVars;
        } else {
            this.env = new HashMap();
        }
        this.testResult = testResult;
    }

    def send(def message) {
        SplunkLogService.send(message);
    }

    @Override
    public String toString() {
        return "RunDelegate on build:"+this.build;
    }
}