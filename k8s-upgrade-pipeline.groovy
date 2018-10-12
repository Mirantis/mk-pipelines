/**
 * Update kuberentes cluster
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *   KUBERNETES_HYPERKUBE_IMAGE Target kubernetes version. May be null in case of reclass-system rollout
 *   KUBERNETES_PAUSE_IMAGE     Kubernetes pause image should have same version as hyperkube. May be null in case of reclass-system rollout
 *   TARGET_UPDATES             Comma separated list of nodes to update (Valid values are ctl,cmp)
 *   CTL_TARGET                 Salt targeted kubernetes CTL nodes (ex. I@kubernetes:master). Kubernetes control plane
 *   CMP_TARGET                 Salt targeted compute nodes (ex. cmp* and 'I@kubernetes:pool') Kubernetes computes
 *   PER_NODE                   Target nodes will be managed one by one (bool)
 *   SIMPLE_UPGRADE             Use previous version of upgrade without conron/drain abilities
 *   UPGRADE_DOCKER             Upgrade docker component
 *   CONFORMANCE_RUN_AFTER      Run Kubernetes conformance tests after update
 *   CONFORMANCE_RUN_BEFORE     Run Kubernetes conformance tests before update
 *   TEST_K8S_API_SERVER        Kubernetes API server address for test execution
 *   ARTIFACTORY_URL            Artifactory URL where docker images located. Needed to correctly fetch conformance images.
 *
**/
def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def updates = TARGET_UPDATES.tokenize(",").collect{it -> it.trim()}
def pepperEnv = "pepperEnv"

def overrideKubernetesImage(pepperEnv) {
    def salt = new com.mirantis.mk.Salt()

    def k8sSaltOverrides = """
        kubernetes_hyperkube_image: ${KUBERNETES_HYPERKUBE_IMAGE}
        kubernetes_pause_image: ${KUBERNETES_PAUSE_IMAGE}
    """
    stage("Override kubernetes images to target version") {
        salt.setSaltOverrides(pepperEnv,  k8sSaltOverrides)
    }
}

def performKubernetesComputeUpdate(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()

    stage("Execute Kubernetes compute update on ${target}") {
        salt.enforceState(pepperEnv, target, 'kubernetes.pool')
        salt.runSaltProcessStep(pepperEnv, target, 'service.restart', ['kubelet'])
    }
}

def performKubernetesControlUpdate(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()

    stage("Execute Kubernetes control plane update on ${target}") {
        salt.enforceStateWithExclude(pepperEnv, target, "kubernetes", "kubernetes.master.setup")
        // Restart kubelet
        salt.runSaltProcessStep(pepperEnv, target, 'service.restart', ['kubelet'])
    }
}

def cordonNode(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()
    def originalTarget = "I@kubernetes:master and not ${target}"

    stage("Cordoning ${target} kubernetes node") {
        def nodeShortName = target.tokenize(".")[0]
        salt.cmdRun(pepperEnv, originalTarget, "kubectl cordon ${nodeShortName}", true, 1)
    }
}

def uncordonNode(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()
    def originalTarget = "I@kubernetes:master and not ${target}"

    stage("Uncordoning ${target} kubernetes node") {
        def nodeShortName = target.tokenize(".")[0]
        salt.cmdRun(pepperEnv, originalTarget, "kubectl uncordon ${nodeShortName}", true, 1)
    }
}

def drainNode(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()
    def originalTarget = "I@kubernetes:master and not ${target}"

    stage("Draining ${target} kubernetes node") {
        def nodeShortName = target.tokenize(".")[0]
        salt.cmdRun(pepperEnv, originalTarget, "kubectl drain --force --ignore-daemonsets --grace-period 100 --timeout 300s --delete-local-data ${nodeShortName}", true, 1)
    }
}

def regenerateCerts(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()

    stage("Regenerate certs for ${target}") {
        salt.enforceState(pepperEnv, target, 'salt.minion.cert')
    }
}

def updateAddons(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()

    stage("Upgrading Addons at ${target}") {
        salt.enforceState(pepperEnv, target, "kubernetes.master.kube-addons")
    }
}

def updateAddonManager(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()

    stage("Upgrading AddonManager at ${target}") {
        salt.enforceState(pepperEnv, target, "kubernetes.master.setup")
    }
}

def upgradeDocker(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()

    stage("Upgrading docker at ${target}") {
        salt.enforceState(pepperEnv, target, 'docker.host')
    }
}

def runConformance(pepperEnv, target, k8s_api, image) {
    def salt = new com.mirantis.mk.Salt()
    def containerName = 'conformance_tests'
    output_file = image.replaceAll('/', '-') + '.output'
    def output_file_full_path = "/tmp/" + image.replaceAll('/', '-') + '.output'
    def artifacts_dir = '_artifacts/'
    salt.cmdRun(pepperEnv, target, "docker rm -f ${containerName}", false)
    salt.cmdRun(pepperEnv, target, "docker run -d --name ${containerName} --net=host -e API_SERVER=${k8s_api} ${image}")
    sleep(10)

    print("Waiting for tests to run...")
    salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', ["docker wait ${containerName}"], null, false)

    print("Writing test results to output file...")
    salt.runSaltProcessStep(pepperEnv, target, 'cmd.run', ["docker logs -t ${containerName} > ${output_file_full_path}"])
    print("Conformance test output saved in " + output_file_full_path)

    // collect output
    sh "mkdir -p ${artifacts_dir}"
    file_content = salt.getFileContent(pepperEnv, target, '/tmp/' + output_file)
    writeFile file: "${artifacts_dir}${output_file}", text: file_content
    sh "cat ${artifacts_dir}${output_file}"
    try {
      sh "cat ${artifacts_dir}${output_file} | grep 'Test Suite Failed' && exit 1 || exit 0"
    } catch (Throwable e) {
      print("Conformance tests failed. Please check output")
      currentBuild.result = "FAILURE"
      currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
      throw e
    }
}

def buildImageURL(pepperEnv, target, mcp_repo) {
    def salt = new com.mirantis.mk.Salt()
    def raw_version = salt.cmdRun(pepperEnv, target, "kubectl version --short -o json")['return'][0].values()[0].replaceAll('Salt command execution success','')
    print("Kubernetes version: " + raw_version)
    def serialized_version = readJSON text: raw_version
    def short_version = (serialized_version.serverVersion.gitVersion =~ /([v])(\d+\.)(\d+\.)(\d+\-)(\d+)/)[0][0]
    print("Kubernetes short version: " + short_version)
    def conformance_image = mcp_repo + "/mirantis/kubernetes/k8s-conformance:" + short_version
    return conformance_image
}

def executeConformance(pepperEnv, target, k8s_api, mcp_repo) {
    stage("Running conformance tests") {
        def image = buildImageURL(pepperEnv, target, mcp_repo)
        print("Using image: " + image)
        runConformance(pepperEnv, target, k8s_api, image)
    }
}


timeout(time: 12, unit: 'HOURS') {
    node() {
        try {

            stage("Setup virtualenv for Pepper") {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            if (CONFORMANCE_RUN_BEFORE.toBoolean()) {
                def target = CTL_TARGET
                def mcp_repo = ARTIFACTORY_URL
                def k8s_api = TEST_K8S_API_SERVER
                firstTarget = salt.getFirstMinion(pepperEnv, target)
                executeConformance(pepperEnv, firstTarget, k8s_api, mcp_repo)
            }

            if ((common.validInputParam('KUBERNETES_HYPERKUBE_IMAGE')) && (common.validInputParam('KUBERNETES_PAUSE_IMAGE'))) {
                overrideKubernetesImage(pepperEnv)
            }

            /*
                * Execute update
            */
            if (updates.contains("ctl")) {
                def target = CTL_TARGET

                if (PER_NODE.toBoolean()) {
                    def targetHosts = salt.getMinionsSorted(pepperEnv, target)

                    for (t in targetHosts) {
                        if (SIMPLE_UPGRADE.toBoolean()) {
                            performKubernetesControlUpdate(pepperEnv, t)
                        } else {
                            cordonNode(pepperEnv, t)
                            drainNode(pepperEnv, t)
                            regenerateCerts(pepperEnv, t)
                            if (UPGRADE_DOCKER.toBoolean()) {
                                upgradeDocker(pepperEnv, t)
                            }
                            performKubernetesControlUpdate(pepperEnv, t)
                            updateAddonManager(pepperEnv, t)
                            uncordonNode(pepperEnv, t)
                        }
                    }
                } else {
                    performKubernetesControlUpdate(pepperEnv, target)
                }
                if (!SIMPLE_UPGRADE.toBoolean()) {
                    // Addons upgrade should be performed after all nodes will upgraded
                    updateAddons(pepperEnv, target)
                    // Wait for 90 sec for addons reconciling
                    sleep(90)
                }
            }

            if (updates.contains("cmp")) {
                def target = CMP_TARGET

                if (PER_NODE.toBoolean()) {
                    def targetHosts = salt.getMinionsSorted(pepperEnv, target)

                    for (t in targetHosts) {
                        if (SIMPLE_UPGRADE.toBoolean()) {
                            performKubernetesComputeUpdate(pepperEnv, t)
                        } else {
                            cordonNode(pepperEnv, t)
                            drainNode(pepperEnv, t)
                            regenerateCerts(pepperEnv, t)
                            if (UPGRADE_DOCKER.toBoolean()) {
                                upgradeDocker(pepperEnv, t)
                            }
                            performKubernetesComputeUpdate(pepperEnv, t)
                            uncordonNode(pepperEnv, t)
                        }
                    }
                } else {
                    performKubernetesComputeUpdate(pepperEnv, target)
                }
            }

            if (CONFORMANCE_RUN_AFTER.toBoolean()) {
                def target = CTL_TARGET
                def mcp_repo = ARTIFACTORY_URL
                def k8s_api = TEST_K8S_API_SERVER
                firstTarget = salt.getFirstMinion(pepperEnv, target)
                executeConformance(pepperEnv, firstTarget, k8s_api, mcp_repo)
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}