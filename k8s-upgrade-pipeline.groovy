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
        salt.enforceState(pepperEnv, target, "kubernetes.master.addons")
        salt.enforceState(pepperEnv, target, "kubernetes.master.setup")
    }
}

def upgradeDocker(pepperEnv, target) {
    def salt = new com.mirantis.mk.Salt()

    stage("Upgrading docker at ${target}") {
        salt.enforceState(pepperEnv, target, 'docker.host')
    }
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {

            stage("Setup virtualenv for Pepper") {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
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
                            updateAddons(pepperEnv, t)
                            uncordonNode(pepperEnv, t)
                        }
                    }
                } else {
                    performKubernetesControlUpdate(pepperEnv, target)
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
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}