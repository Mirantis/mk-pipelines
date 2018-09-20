def gerrit = new com.mirantis.mk.Gerrit()
def common = new com.mirantis.mk.Common()


def slaveNode = env.SLAVE_NODE ?: 'python&&docker'
def gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'

def gerritRef = env.GERRIT_REFSPEC ?: null
def defaultGitRef = env.DEFAULT_GIT_REF ?: null
def defaultGitUrl = env.DEFAULT_GIT_URL ?: null

def checkouted = false
def merged = false
def systemRefspec = "HEAD"
def formulasRevision = 'testing'

timeout(time: 12, unit: 'HOURS') {
    node(slaveNode) {
        try {
            stage("Checkout") {
                if (gerritRef) {
                    // job is triggered by Gerrit
                    // test if change aren't already merged
                    def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, gerritCredentials)
                    merged = gerritChange.status == "MERGED"
                    if (!merged) {
                        checkouted = gerrit.gerritPatchsetCheckout([
                            credentialsId: gerritCredentials
                        ])
                        systemRefspec = GERRIT_REFSPEC
                    }
                    // change defaultGit variables if job triggered from Gerrit
                    defaultGitUrl = "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
                } else if (defaultGitRef && defaultGitUrl) {
                    checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", gerritCredentials)
                }
            }

            stage("Test") {
                if (merged) {
                    common.successMsg("Gerrit change is already merged, no need to test them")
                } else {
                    if (checkouted) {

                        def documentationOnly = false
                        if (gerritRef) {
                            documentationOnly = sh(script: "git diff-tree --no-commit-id --name-only -r HEAD | grep -v .releasenotes", returnStatus: true) == 1
                        }

                        sh("git diff-tree --no-commit-id --diff-filter=d --name-only -r HEAD  | grep .yml | xargs -I {}  python -c \"import yaml; yaml.load(open('{}', 'r'))\" \\;")

                        def branches = [:]
                        def testModels = documentationOnly ? [] : TEST_MODELS.split(',')
                        if (['master'].contains(env.GERRIT_BRANCH)) {
                            for (int i = 0; i < testModels.size(); i++) {
                                def cluster = testModels[i]
                                def clusterGitUrl = defaultGitUrl.substring(0, defaultGitUrl.lastIndexOf("/") + 1) + cluster
                                branches["${cluster}"] = {
                                    build job: "test-salt-model-${cluster}", parameters: [
                                        [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: clusterGitUrl],
                                        [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: "HEAD"],
                                        [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: defaultGitUrl],
                                        [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: systemRefspec],
                                        [$class: 'StringParameterValue', name: 'FORMULAS_REVISION', value: formulasRevision],
                                    ]
                                }
                            }
                        } else {
                            common.warningMsg("Tests for ${testModels} skipped!")
                        }
                        branches["cookiecutter"] = {
                            build job: "test-mk-cookiecutter-templates", parameters: [
                                [$class: 'StringParameterValue', name: 'RECLASS_SYSTEM_URL', value: defaultGitUrl],
                                [$class: 'StringParameterValue', name: 'RECLASS_SYSTEM_GIT_REF', value: systemRefspec],
                                [$class: 'StringParameterValue', name: 'DISTRIB_REVISION', value: formulasRevision]

                            ]
                        }
                        parallel branches
                    } else {
                        error("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
                    }
                }
            }
        } catch (Throwable e) {
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        } finally {
            common.sendNotification(currentBuild.result, "", ["slack"])
        }
    }
}
