/**
 * Tests model manager UI
 * API_GERRIT_REPO
 * API_GERRIT_REF
 * UI_GERRIT_REPO
 * UI_GERRIT_REF
 * API_DOCKER_IMG
 * UI_DOCKER_IMG
 * CVP_DOCKER_IMG
 * DOCKER_REGISTRY
 * DOCKER_REVIEW_REGISTRY
 * MCP_VERSION
 * FLAVOR
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def python = new com.mirantis.mk.Python()

def gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'
def slaveNode = env.SLAVE_NODE ?: 'python&&docker'
def event = env.GERRIT_EVENT_TYPE ?: null
def defaultRef = 'master'
def apiGerritRef = env.API_GERRIT_REF ?: defaultRef
def uiGerritRef = env.UI_GERRIT_REF ?: defaultRef
def version = env.MCP_VERSION ?: 'nightly'
def dockerRegistry = env.DOCKER_REGISTRY ?: 'docker-prod-local.docker.mirantis.net'
def dockerReviewRegistry = env.DOCKER_REVIEW_REGISTRY ?: 'docker-dev-local.docker.mirantis.net'
def cvpImageName = env.CVP_DOCKER_IMG ? "${dockerRegistry}/${env.CVP_DOCKER_IMG}:${version}" : "${dockerRegistry}/mirantis/cvp/cvp-trymcp-tests:${version}"

def checkouted = false
def testReportHTMLFile = 'reports/report.html'
def testReportXMLFile = 'reports/report.xml'
def manualTrigger = false

def apiProject = 'operations-api'
def uiProject = 'operations-ui'
def apiImage
def uiImage
def component = ''

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        sh "mkdir -p reports ${apiProject} ${uiProject}"
        def testImage = docker.image(cvpImageName)
        def testImageOptions = "-u root:root --network=host -v ${env.WORKSPACE}/reports:/var/lib/qa_reports --entrypoint=''"
        try {
            stage("checkout") {
                if (event) {
                    dir(env.FLAVOR) {
                        // job is triggered by Gerrit
                        def gerritChange = gerrit.getGerritChange(env.GERRIT_NAME, env.GERRIT_HOST, env.GERRIT_CHANGE_NUMBER, gerritCredentials, true)
                        if (gerritChange.commitMessage.contains("WIP")) {
                            common.successMsg("Commit message contains WIP, skipping tests") // do nothing
                        } else {
                            // test if change aren't already merged
                            def merged = gerritChange.status == "MERGED"
                            if (!merged) {
                                checkouted = gerrit.gerritPatchsetCheckout([
                                        credentialsId: gerritCredentials
                                ])
                            } else {
                                common.successMsg("Change ${env.GERRIT_CHANGE_NUMBER} is already merged, no need to test them")
                            }
                        }
                    }
                } else {
                    common.successMsg('Gerrit variables are not set. Assuming it is manual trigger')
                    manualTrigger = true
                }

                if (checkouted) {
                    if (env.FLAVOR == apiProject) {
                        // Second project is UI
                        checkout([
                                $class           : 'GitSCM',
                                branches         : [[name: 'FETCH_HEAD'],],
                                extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: uiProject]],
                                userRemoteConfigs: [[url: env.UI_GERRIT_REPO, refspec: uiGerritRef, credentialsId: gerritCredentials],],
                        ])
                        apiImage = docker.image("${dockerReviewRegistry}/review/${env.FLAVOR}-${env.GERRIT_CHANGE_NUMBER}:${env.GERRIT_PATCHSET_NUMBER}")
                        uiImage = docker.image("${dockerRegistry}/${env.UI_DOCKER_IMG ?: "mirantis/model-generator/operations-ui"}:${version}")
                        component = "-k 'api'"
                    } else if (env.FLAVOR == uiProject) {
                        // Second project is API
                        checkout([
                                $class           : 'GitSCM',
                                branches         : [[name: 'FETCH_HEAD'],],
                                extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: apiProject]],
                                userRemoteConfigs: [[url: env.API_GERRIT_REPO, refspec: apiGerritRef, credentialsId: gerritCredentials],],
                        ])
                        apiImage = docker.image("${dockerRegistry}/${env.API_DOCKER_IMG ?: "mirantis/model-generator/operations-api"}:${version}")
                        uiImage = docker.image("${dockerReviewRegistry}/review/${env.FLAVOR}-${env.GERRIT_CHANGE_NUMBER}:${env.GERRIT_PATCHSET_NUMBER}")
                        component = "-k 'ui'"
                    }
                } else if (manualTrigger) {
                    checkout([
                            $class           : 'GitSCM',
                            branches         : [[name: 'FETCH_HEAD'],],
                            extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: apiProject]],
                            userRemoteConfigs: [[url: env.API_GERRIT_REPO, refspec: apiGerritRef, credentialsId: gerritCredentials],],
                    ])
                    checkout([
                            $class           : 'GitSCM',
                            branches         : [[name: 'FETCH_HEAD'],],
                            extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: uiProject]],
                            userRemoteConfigs: [[url: env.UI_GERRIT_REPO, refspec: uiGerritRef, credentialsId: gerritCredentials],],
                    ])
                    apiImage = docker.image("${dockerRegistry}/${env.API_DOCKER_IMG ?: "mirantis/model-generator/operations-api"}:${version}")
                    uiImage = docker.image("${dockerRegistry}/${env.UI_DOCKER_IMG ?: "mirantis/model-generator/operations-ui"}:${version}")
                } else {
                    throw new Exception('Cannot checkout gerrit repositories. Please verify that parameters for repositories are properly set')
                }
            }

            stage('Pull docker images') {
                common.retry(3, 5) {
                    apiImage.pull()
                }
                common.retry(3, 5) {
                    uiImage.pull()
                }
                common.retry(3, 5) {
                    testImage.pull()
                }
            }

            stage('Prepare and run docker compose services') {
                python.setupVirtualenv("${env.WORKSPACE}/venv", 'python2', ['docker-compose==1.22.0'])

                // Make sure cockroach_data is cleaned up
                if(fileExists("${apiProject}/cockroach_data")) {
                    testImage.inside(testImageOptions) {
                        sh("rm -rf ${env.WORKSPACE}/${apiProject}/cockroach_data")
                    }
                }

                dir(apiProject) {
                    python.runVirtualenvCommand("${env.WORKSPACE}/venv",
                            "export IMAGE=${apiImage.id}; ./bootstrap_env.sh up")
                    common.retry(5, 20) {
                        sh 'curl -v http://127.0.0.1:8001/api/v1 > /dev/null'
                    }
                }
                dir(uiProject) {
                    python.runVirtualenvCommand("${env.WORKSPACE}/venv",
                            "export IMAGE=${uiImage.id}; docker-compose -f docker-compose-test.yml up -d")
                    common.retry(5, 20) {
                        sh 'curl -v http://127.0.0.1:3000 > /dev/null'
                    }
                }
            }

            stage('Test') {
                testImage.inside(testImageOptions) {
                    sh """
                        export TEST_LOGIN=test
                        export TEST_PASSWORD=default
                        export TEST_MODELD_URL=127.0.0.1
                        export TEST_MODELD_PORT=3000
                        cd /var/lib/trymcp-tests
                        pytest -m 'not trymcp' ${component}
                    """
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        } finally {
            if (fileExists(testReportHTMLFile)) {
                archiveArtifacts artifacts: testReportHTMLFile
            }
            if (fileExists(testReportXMLFile)) {
                archiveArtifacts artifacts: testReportXMLFile
            }
            stage("Cleanup") {
                if (fileExists("${env.WORKSPACE}/venv")) {
                    dir(apiProject) {
                        python.runVirtualenvCommand("${env.WORKSPACE}/venv", "./bootstrap_env.sh down || true")
                    }
                    dir(uiProject) {
                        python.runVirtualenvCommand("${env.WORKSPACE}/venv", "docker-compose down || true")
                    }
                    sh "rm -rf ${env.WORKSPACE}/venv/"
                }
                if (apiImage && apiImage.id) {
                    sh "docker rmi ${apiImage.id}"
                }
                if (uiImage && uiImage.id) {
                    sh "docker rmi ${uiImage.id}"
                }
                // Remove everything what is owned by root
                testImage.inside(testImageOptions) {
                    sh("rm -rf /var/lib/qa_reports/* ${env.WORKSPACE}/${apiProject} ${env.WORKSPACE}/${uiProject}")
                }
            }
        }
    }
}
