/**
 * Tests model manager UI
 * DEFAULT_GIT_REF
 * DEFAULT_GIT_URL
 * NPM_DOCKER_IMG
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def dockerLib = new com.mirantis.mk.Docker()

def gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'
def slaveNode = env.SLAVE_NODE ?: 'python&&docker'
def gerritRef = env.GERRIT_REFSPEC ?: null
def defaultGitRef = env.DEFAULT_GIT_REF ?: null
def defaultGitUrl = env.DEFAULT_GIT_URL ?: null

def checkouted = false
def testReportFile = 'test-report.html'

timeout(time: 30, unit: 'MINUTES') {
    node(slaveNode) {
        def img = dockerLib.getImage(env.NPM_DOCKER_IMG, "npm:8.12.0")
        try {
            if (fileExists('build') || fileExists('.npm')) {
                common.infoMsg('Cleaning test env')
                img.inside("-u root:root -v ${env.WORKSPACE}/:/operations-ui/") {
                    sh("rm -rf /operations-ui/build/ /operations-ui/.npm")
                }
            }
            stage("checkout") {
                if (gerritRef) {
                    // job is triggered by Gerrit
                    def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, gerritCredentials, true)
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
                            common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to test them")
                        }
                    }
                } else if (defaultGitRef && defaultGitUrl) {
                    checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", gerritCredentials)
                } else {
                    throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF are null")
                }
            }

            if (checkouted) {
                stage("test") {
                    img.inside("-u root:root -v ${env.WORKSPACE}/:/operations-ui/ -e npm_config_cache=/operations-ui/.npm -e CI=true") {
                        sh('''#!/bin/bash -xe
                          cd /operations-ui
                          npm install
                          npm test
                          ''')
                    }
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
            stage("Cleanup"){
                img.inside("-u root:root -v ${env.WORKSPACE}/:/operations-ui/") {
                    sh("rm -rf /operations-ui/*")
                }
            }
        }
    }
}
