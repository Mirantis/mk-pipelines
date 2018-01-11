/*
 *
 * Launcher for TCP-QA tests
 *
 * Expected parameters:
 *   ENV_NAME                 Environment name
 *   REPOSITORY_SUITE         Packagfe repository (stable, testing, nightly)
 *   TEST_GROUP               TCQ-QA test group or a test name
 *   LAB_CONFIG_NAME          Environment confguration for deploy
 *   ADDITIONAL_PARAMETERS    Additional shell environment variables
 *   TCP_QA_COMMIT            TCQ-QA commit or branch
 *   TCP_QA_REFS              TCP-QA refs for cherry-pick
 *   TCP_QA_GERRIT_HOST       TCQ-QA repo Gerrit host
 *   SHUTDOWN_ENV_ON_TEARDOWN Destroy virtual environment after test run
 *   KEEP_BEFORE              Keep virtual environment before run tests or erase it
 *   KEEP_AFTER               Keep virtual environment after run tests or erase it
 *   VENV_PATH                Path to python virtual environment
 *   SLAVE_LABELS             Jenkins slave node labels
 *   TEST_TIMEOUT             Timeout for test job in minutes
 *   UPLOAD_RESULTS           Upload or not test results to Testrail
 *   TESTRAIL_MILESTONE       Testrail milestone
 *   TESTRAIL_TEST_SUITE      Testrail test suite
 *
 */

git = new com.mirantis.mcp.Git()
qaCommon = new com.mirantis.tcp_qa.Common()
testRunner = new com.mirantis.tcp_qa.RunTest()
environment = new com.mirantis.tcp_qa.EnvActions()

def runTests() {
    def additionalParameters = []

    if (!env.ENV_NAME) {
        error("Error! Test path (ENV_NAME) is not specified!")
    }

    if (!env.REPOSITORY_SUITE) {
        error("Error! Test path (REPOSITORY_SUITE) is not specified!")
    }
    if (!env.TEST_GROUP){
        error("Error! Test path (TEST_GROUP) is not specified!")
    }
    if (!env.LAB_CONFIG_NAME){
        error("Error! Test path (LAB_CONFIG_NAME) is not specified!")
    }

    if (params.ADDITIONAL_PARAMETERS) {
        for (p in params.ADDITIONAL_PARAMETERS.split('\n')) {
            additionalParameters << p
        }
        echo("Additional parameters are: ${additionalParameters.join(' ')}")
    }

    withEnv(additionalParameters) {

        stage('Clone tcp-qa') {
            git.gitCheckout ([
                protocol: 'https',
                port: '443',
                branch : env.TCP_QA_COMMIT,
                host : 'github.com',
                project : 'Mirantis/tcp-qa',
                targetDir : '.'
            ])
            if ( env.TCP_QA_REFS && ! env.TCP_QA_REFS.equals('none') ) {
                def refs = "${env.TCP_QA_REFS}".split("\n")
                qaCommon.getCustomRefs(env.TCP_QA_GERRIT_HOST, 'Mirantis/tcp-qa', env.WORKSPACE, refs)
            }
        }

        stage('Update python venv') {
            environment.prepareEnv()
        }

        stage('Run tests') {
            if (!((env.KEEP_BEFORE == "yes") || (env.KEEP_BEFORE == "true"))){
                environment.eraseEnv()
            }
            sh """
            . ${VENV_PATH}/bin/activate

            cd tcp_tests
            if ! py.test -vvv -s -p no:django -p no:ipdb --junit-xml=../nosetests.xml -k ${TEST_GROUP}; then
              echo "Tests failed!"
              exit 1
            fi
            """

            // testRunner.runTest("-k ${env.TEST_GROUP}", jobSetParameters)
        }

        if (!((env.KEEP_AFTER == "yes") || (env.KEEP_AFTER == "true"))){
            environment.eraseEnv()
        }
    }
}

def uploadResults(){
    stage('Upload tests results'){
        def thisBuildUrl = "${JENKINS_URL}/job/${JOB_NAME}/${BUILD_NUMBER}/"
        def testPlanName = "${TESTRAIL_MILESTONE} Integration-${new Date().format('yyyy-MM-dd')}"

        qaCommon.uploadResultsTestRail([
            junitXml: "${WORKSPACE}/nosetests.xml",
            testPlanName: testPlanName,
            testSuiteName: "${TESTRAIL_TEST_SUITE}",
            testrailMilestone: "${TESTRAIL_MILESTONE}",
            jobURL: thisBuildUrl,
        ])
    }
}

def runSlavesLabels = params.SLAVE_LABELS ?: 'tcp-qa-slaves'
def runTimeout = params.TEST_TIMEOUT ?: 240
timeout(time: 12, unit: 'HOURS') {
    node (runSlavesLabels) {
        try {
          timeout(time: runTimeout.toInteger(), unit: 'MINUTES') {
            runTests()
          }
        }
        catch (err) {
            echo "Failed: ${err}"
            currentBuild.result = 'FAILURE'
        }
        finally {
            if (env.UPLOAD_RESULTS == "true") {
                testRunUrl = uploadResults()
                currentBuild.description = """
                <a href="${testRunUrl}">TestRail report</a>
                """
            }
            environment.destroyEnv()
            archiveArtifacts allowEmptyArchive: true, artifacts: 'nosetests.xml,tests.log,*.ini', excludes: null
            junit keepLongStdio: false, testResults: 'nosetests.xml'
        }
    }
}
