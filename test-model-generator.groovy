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
def dockerLib = new com.mirantis.mk.Docker()

def gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'
def slaveNode = env.SLAVE_NODE ?: 'python&&docker'
def event = env.GERRIT_EVENT_TYPE ?: null
def defaultRef = 'master'
def apiGerritRef = env.API_GERRIT_REF ?: defaultRef
def uiGerritRef = env.UI_GERRIT_REF ?: defaultRef
def gerritProject = env.GERRIT_PROJECT ?: null
def version = env.MCP_VERSION ?: 'testing'
def dockerRegistry = env.DOCKER_REGISTRY ?: 'docker-prod-local.docker.mirantis.net'
def dockerReviewRegistry = env.DOCKER_REVIEW_REGISTRY ?: 'docker-dev-local.docker.mirantis.net'

def checkouted = false
def testReportFile = "${env.WORKSPACE}/reports/report.html"
def manualTrigger = false

def apiProject = 'operations-api'
def uiProject = 'operations-ui'
def apiImage
def uiImage

timeout(time: 1, unit: 'HOURS') {
    node(slaveNode) {
        sh "mkdir -p reports ${apiProject} ${uiProject}"
        def img = dockerLib.getImage("${env.CVP_DOCKER_IMG}:${version}", "${dockerRegistry}/mirantis/cvp/cvp-trymcp-tests:${version}")
        try {
            stage("checkout") {
                if (event) {
                    dir(gerritProject) {
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
                        apiImage = image("${dockerReviewRegistry}/review/${env.FLAVOR}-${env.GERRIT_CHANGE_NUMBER}:${env.GERRIT_PATCHSET_NUMBER}")
                        uiImage = image("${dockerRegistry}/${env.UI_DOCKER_IMG ?: "mirantis/model-generator/operations-ui"}:${version}")
                    } else if (env.FLAVOR == uiProject) {
                        // Second project is API
                        checkout([
                                $class           : 'GitSCM',
                                branches         : [[name: 'FETCH_HEAD'],],
                                extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: apiProject]],
                                userRemoteConfigs: [[url: env.API_GERRIT_REPO, refspec: apiGerritRef, credentialsId: gerritCredentials],],
                        ])
                        apiImage = image("${dockerRegistry}/${env.API_DOCKER_IMG ?: "mirantis/model-generator/operations-api"}:${version}")
                        uiImage = image("${dockerReviewRegistry}/review/${env.FLAVOR}-${env.GERRIT_CHANGE_NUMBER}:${env.GERRIT_PATCHSET_NUMBER}")
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
                    apiImage = image("${dockerRegistry}/${env.API_DOCKER_IMG ?: "mirantis/model-generator/operations-api"}:${version}")
                    uiImage = image("${dockerRegistry}/${env.UI_DOCKER_IMG ?: "mirantis/model-generator/operations-ui"}:${version}")
                } else {
                    throw new Exception('Cannot checkout gerrit repositories. Please verify that parameters for repositories are properly set')
                }
            }

            stage('Pull docker images') {
                apiImage.pull()
                uiImage.pull()
            }

            stage('Prepare and run docker compose services') {
                sh """
                    virtualenv ${env.WORKSPACE}/venv
                    source ${env.WORKSPACE}/venv/bin/activate
                    pip install docker-compose==1.22.0
                """

                dir(apiProject) {
                    sh """
                        export IMAGE=${apiImage}
                        source ${env.WORKSPACE}/venv/bin/activate && ./bootstrap_env.sh up
                    """
                }
                dir(uiProject) {
                    sh """
                        export IMAGE=${uiImage}
                        source ${env.WORKSPACE}/venv/bin/activate && docker-compose up -d
                    """
                }
            }

            stage("test") {
                img.inside("-u root:root -v ${env.WORKSPACE}/reports:/var/lib/qa_reports") {
                    sh "pytest -m 'not trymcp'"
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        } finally {
            if (fileExists(testReportFile)) {
                archiveArtifacts artifacts: testReportFile
            }
            stage("Cleanup") {
                if (fileExists("${env.WORKSPACE}/venv")) {
                    dir(apiProject) {
                        sh "source ${env.WORKSPACE}/venv/bin/activate && ./bootstrap_env.sh down || true"
                    }
                    dir(uiProject) {
                        sh "source ${env.WORKSPACE}/venv/bin/activate && docker-compose down || true"
                    }
                    sh "rm -rf ${env.WORKSPACE}/venv/"
                }
                if (apiImage && apiImage.id) {
                    sh "docker rm -f ${apiImage.id}"
                }
                if (uiImage && uiImage.id) {
                    sh "docker rm -f ${uiImage.id}"
                }
                // Remove everything what is owned by root
                img.inside("-u root:root -v ${env.WORKSPACE}:/temp") {
                    sh('rm -rf /temp/reports/* /temp/cockroach_data')
                }
            }
        }
    }
}
