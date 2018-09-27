/**
 * Gerrit gating pipeline
 * CREDENTIALS_ID - Gerrit credentails ID
 * JOBS_NAMESPACE - Gerrit gating jobs namespace (mk, contrail, ...)
 *
 **/

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()


@NonCPS
def isJobExists(jobName) {
    return Jenkins.instance.items.find { it -> it.name.equals(jobName) }
}

slaveNode = env.SLAVE_NODE ?: 'docker'

timeout(time: 12, unit: 'HOURS') {
    node(slaveNode) {
        try {
            // test if change is not already merged
            ssh.prepareSshAgentKey(CREDENTIALS_ID)
            ssh.ensureKnownHosts(GERRIT_HOST)
            def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID, true)
            def doSubmit = false
            def giveVerify = false
            stage("test") {
                if (gerritChange.status != "MERGED" && !SKIP_TEST.equals("true")) {
                    // test max CodeReview
                    if (gerrit.patchsetHasApproval(gerritChange.currentPatchSet, "Code-Review", "+")) {
                        doSubmit = true
                        def gerritProjectArray = GERRIT_PROJECT.tokenize("/")
                        def gerritProject = gerritProjectArray[gerritProjectArray.size() - 1]
                        def jobsNamespace = JOBS_NAMESPACE
                        def plural_namespaces = ['salt-formulas', 'salt-models']
                        // remove plural s on the end of job namespace
                        if (JOBS_NAMESPACE in plural_namespaces) {
                            jobsNamespace = JOBS_NAMESPACE.substring(0, JOBS_NAMESPACE.length() - 1)
                        }
                        // salt-formulas tests have -latest on end of the name
                        if (JOBS_NAMESPACE.equals("salt-formulas")) {
                            gerritProject = gerritProject + "-latest"
                        }
                        def testJob = String.format("test-%s-%s", jobsNamespace, gerritProject)
                        if (isJobExists(testJob)) {
                            common.infoMsg("Test job ${testJob} found, running")
                            def patchsetVerified = gerrit.patchsetHasApproval(gerritChange.currentPatchSet, "Verified", "+")
                            build job: testJob, parameters: [
                                [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"],
                                [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: GERRIT_REFSPEC]
                            ]
                            giveVerify = true
                        } else {
                            common.infoMsg("Test job ${testJob} not found")
                        }
                    } else {
                        common.errorMsg("Change don't have a CodeReview, skipping gate")
                    }
                } else {
                    common.infoMsg("Test job skipped")
                }
            }
            stage("submit review") {
                if (gerritChange.status == "MERGED") {
                    common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to gate them")
                } else if (doSubmit) {
                    if (giveVerify) {
                        common.warningMsg("Change ${GERRIT_CHANGE_NUMBER} don't have a Verified, but tests were successful, so adding Verified and submitting")
                        ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit review --verified +1 --submit %s,%s", GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
                    } else {
                        ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit review --submit %s,%s", GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
                    }
                    common.infoMsg(String.format("Gerrit review %s,%s submitted", GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}