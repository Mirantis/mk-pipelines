/**
 *
 * Test Drivetrain pipeline
 *
 * Expected parameters:
 *   COOKIECUTTER_TEMPLATE_CONTEXT                 Template context for CookieCutter
 *   SOURCE_MCP_VERSION                            MCP version to start with
 *   TARGET_MCP_VERSION                            MCP version to upgrade to
 *   FUNC_TEST_SETTINGS                            Settings for functional tests
 */


common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
python = new com.mirantis.mk.Python()

pepperEnv = "pepperEnv"

def setupPepperVirtualenv(path, url, creds) {
    requirements = ['salt-pepper>=0.5.2,<0.5.4']
    python.setupVirtualenv(path, 'python2', requirements, null, true, true)
    rcFile = "${path}/pepperrc"
    rc = """\
[main]
SALTAPI_EAUTH=pam
SALTAPI_URL=${url}
SALTAPI_USER=${creds.username}
SALTAPI_PASS=${creds.password}
"""
    writeFile file: rcFile, text: rc
    return rcFile
}

def runJobOnJenkins(jenkinsUrl, userName, password, jobName, parameters){
    def jenkinsDownCmd = "curl -OL ${jenkinsUrl}/jnlpJars/jenkins-cli.jar --output ./jenkins-cli.jar"
    def runJobFromSaltMasterCmd = "java -jar jenkins-cli.jar -s ${jenkinsUrl} -noKeyAuth -auth admin:${password} build ${jobName} ${parameters} -s | grep -E 'SUCCESS|UNSTABLE'"
    salt.cmdRun(pepperEnv, "I@salt:master", jenkinsDownCmd)
    salt.cmdRun(pepperEnv, "I@salt:master", runJobFromSaltMasterCmd)
}

timeout(time: 12, unit: 'HOURS') {
    node("python") {
        try {
            def mcpEnvJob
            def saltReturn
            def saltCreds = [:]

            stage('Trigger deploy job') {
                mcpEnvJob = build(job: "create-mcp-env", parameters: [
                    [$class: 'StringParameterValue', name: 'OS_AZ', value: 'mcp-mk'],
                    [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: 'mcp-mk'],
                    [$class: 'StringParameterValue', name: 'STACK_NAME', value: 'jenkins-drivetrain-test-' + currentBuild.number],
                    [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: 'core,kvm,cicd'],
                    [$class: 'BooleanParameterValue', name: 'STACK_FULL', value: true],
                    [$class: 'BooleanParameterValue', name: 'RUN_TESTS', value: false],
                    [$class: 'TextParameterValue', name: 'COOKIECUTTER_TEMPLATE_CONTEXT', value: COOKIECUTTER_TEMPLATE_CONTEXT]
                ])
            }

            def mcpEnvJobDesc = mcpEnvJob.getDescription().tokenize(" ")
            def mcpEnvJobIP = mcpEnvJobDesc[2]
            def saltMasterUrl = "http://${mcpEnvJobIP}:6969"
            def script = "println(com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials.class,jenkins.model.Jenkins.instance).findAll {cred -> cred.id == 'salt'}[0].password)"
            def saltPasswd = sh(returnStdout: true, script: "curl -d \"script=${script}\" --user admin:r00tme http://${mcpEnvJobIP}:8081/scriptText")
            saltPasswd = saltPasswd.trim()
            saltCreds.put("username", "salt")
            saltCreds.put("password", saltPasswd)
            setupPepperVirtualenv(pepperEnv, saltMasterUrl, saltCreds)
            saltReturn = salt.getPillar(pepperEnv, 'I@jenkins:client and not I@salt:master', '_param:openldap_admin_password')
            def stackCicdPassword = saltReturn.get("return")[0].values()[0]
            saltReturn = salt.getPillar(pepperEnv, 'I@jenkins:client and not I@salt:master', 'jenkins:client:master:host')
            def stackCicdAddr = saltReturn.get("return")[0].values()[0]
            def jenkinsUrl = "http://${stackCicdAddr}:8081"

            stage('Run CVP before upgrade') {
                runJobOnJenkins(jenkinsUrl, "admin", stackCicdPassword, "cvp-sanity", "[{'name':'SANITY_TESTS_SET', 'value':'test_drivetrain.py'},{'name':'SANITY_TESTS_SETTINGS', 'value':'drivetrain_version=\"${SOURCE_MCP_VERSION}\"'}]")
                //runJobOnJenkins(jenkinsUrl, "admin", stackCicdPassword, "cvp-dt-func", "[{'name':'SETTINGS', 'value':'${FUNC_TEST_SETTINGS}'}]")
            }

            stage('Run Upgrade on DriveTrain') {
                runJobOnJenkins(jenkinsUrl, "admin", stackCicdPassword, "upgrade-mcp-release", "[{'name':'MCP_VERSION', 'value':'${TARGET_MCP_VERSION}'}]")
            }

            stage('Run CVP after upgrade') {
                runJobOnJenkins(jenkinsUrl, "admin", stackCicdPassword, "cvp-sanity", "[{'name':'SANITY_TESTS_SET', 'value':'test_drivetrain.py'},{'name':'SANITY_TESTS_SETTINGS', 'value':'drivetrain_version=\"${TARGET_MCP_VERSION}\"'}]")
                //runJobOnJenkins(jenkinsUrl, "admin", stackCicdPassword, "cvp-dt-func", "[{'name':'SETTINGS', 'value':'${FUNC_TEST_SETTINGS}'}]")
            }

        } catch (Throwable e) {
            currentBuild.result = 'FAILURE'
            throw e
        }
    }
}