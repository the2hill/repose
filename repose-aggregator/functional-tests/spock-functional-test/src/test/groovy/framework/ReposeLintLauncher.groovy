/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package framework

import org.linkedin.util.clock.SystemClock
import org.rackspace.deproxy.PortFinder

import java.util.concurrent.TimeoutException

import static org.linkedin.groovy.util.concurrent.GroovyConcurrentUtils.waitForCondition

class ReposeLintLauncher {

    def boolean debugEnabled
    def boolean doSuspend
    def String reposeLintJar
    def String configDir
    def String reposeVer
    def String command

    def clock = new SystemClock()

    def debugPort = null
    def additionalEnvironment = [:]

    Process process

    def ReposeConfigurationProvider configurationProvider

    ReposeLintLauncher(ReposeConfigurationProvider configurationProvider,
                       TestProperties properties,
                       String command) {
        this(configurationProvider,
                properties.reposeLintJar,
                properties.configDirectory,
                properties.reposeVersion,
                command
        )
    }

    ReposeLintLauncher(ReposeConfigurationProvider configurationProvider,
                       String reposeLintJar,
                       String configDir,
                       String reposeVer,
                       String command) {
        TestProperties
        this.configurationProvider = configurationProvider
        this.reposeLintJar = reposeLintJar
        this.configDir = configDir
        this.reposeVer = reposeVer
        this.command = command
    }

    void start() {
        this.start([:])
    }

    void start(Map params) {
        boolean killOthersBeforeStarting = true
        if (params.containsKey("killOthersBeforeStarting")) {
            killOthersBeforeStarting = params.killOthersBeforeStarting
        }

        start(killOthersBeforeStarting)
    }

    /**
     * @param killOthersBeforeStarting
     */
    void start(boolean killOthersBeforeStarting) {
        File jarFile = new File(reposeLintJar)
        if (!jarFile.exists() || !jarFile.isFile()) {
            throw new FileNotFoundException("Missing or invalid Repose Lint Jar file.")
        }

        File configFolder = new File(configDir)
        if (!configFolder.exists() || !configFolder.isDirectory()) {
            throw new FileNotFoundException("Missing or invalid configuration folder.")
        }

        if (killOthersBeforeStarting) {
            waitForCondition(clock, '5s', '1s', {
                killIfUp()
                !isUp()
            })
        }

        def debugProps = ""

        if (debugEnabled) {
            if (!debugPort) {
                debugPort = PortFinder.Singleton.getNextOpenPort()
            }
            debugProps = "-Xdebug -Xrunjdwp:transport=dt_socket,address=${debugPort},server=y,suspend="
            if (doSuspend) {
                debugProps += "y"
                println("\n\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n\nConnect debugger to repose-lint on port: ${debugPort}\n\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n\n")
            } else {
                debugProps += "n"
            }
        }

        def cmd = "java $debugProps -jar $reposeLintJar $command -r $reposeVer -c $configDir"
        println("Running repose-lint")

        def th = new Thread({
            //Construct a new environment, including all from the previous, and then overriding with our new one
            def newEnv = new HashMap<String, String>()
            newEnv.putAll(System.getenv())

            additionalEnvironment.each { k, v ->
                newEnv.put(k, v) //Should override anything, if there's anything to override
            }
            def envList = newEnv.collect { k, v -> "$k=$v" }
            this.process = cmd.execute(envList, null)
            this.process.consumeProcessOutput(System.out, System.err)
        })

        th.run()
        th.join()
    }

    void stop() {
        this.stop([:])
    }

    void stop(Map params) {
        def timeout = params?.timeout ?: 45000
        def throwExceptionOnKill = true

        if (params.containsKey("throwExceptionOnKill")) {
            throwExceptionOnKill = params.throwExceptionOnKill
        }

        stop(timeout, throwExceptionOnKill)
    }

    void stop(int timeout, boolean throwExceptionOnKill) {
        try {
            println("Stopping Repose Lint");
            this.process?.destroy()

            print("Waiting for Repose to shutdown")
            waitForCondition(clock, "${timeout}", '1s', {
                print(".")
                !isUp()
            })

            println()
        } catch (IOException ioex) {
            this.process.waitForOrKill(5000)
            killIfUp()
            if (throwExceptionOnKill) {
                throw new TimeoutException("An error occurred while attempting to stop Repose Controller. Reason: " + ioex.getMessage());
            }
        } finally {
            configurationProvider.cleanConfigDirectory()
        }
    }

    void enableDebug() {
        this.debugEnabled = true
    }

    void enableSuspend() {
        this.debugEnabled = true
        this.doSuspend = true
    }

    /**
     * This takes a single string and will append it to the list of environment vars to be set for the .execute() method
     * Following docs from: http://groovy.codehaus.org/groovy-jdk/java/lang/String.html#execute%28java.util.List,%20java.io.File%29
     * @param environmentPair
     */
    void addToEnvironment(String key, String value) {
        additionalEnvironment.put(key, value)
    }

    public static boolean isUp() {
        println TestUtils.getJvmProcesses()
        return TestUtils.getJvmProcesses().contains("repose-lint.jar")
    }

    private static void killIfUp() {
        String processes = TestUtils.getJvmProcesses()
        def regex = /(\d*) repose-lint.jar .*spocktest .*/
        def matcher = (processes =~ regex)
        if (matcher.size() > 0) {
            for (int i = 1; i <= matcher.size(); i++) {
                String pid = matcher[0][i]

                if (pid != null && !pid.isEmpty()) {
                    println("Killing running repose-lint process: " + pid)
                    Runtime rt = Runtime.getRuntime();
                    if (System.getProperty("os.name").toLowerCase().indexOf("windows") > -1)
                        rt.exec("taskkill " + pid.toInteger());
                    else
                        rt.exec("kill -9 " + pid.toInteger());
                }
            }
        }
    }
}
