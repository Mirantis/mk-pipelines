def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"

targetExpression = TARGET_MINIONS ? TARGET_MINIONS : "E@kvm01.*"
timeout(time: 12, unit: 'HOURS') {
    node() {
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        common.infoMsg("Enforcing kubernetes state..")
        stage("Update k8s control") {
            salt.enforceState(
                pepperEnv,
                targetExpression,
                'kubernetes.control',
                true
            )
        }
        stage("Update components") {
            common.infoMsg("Setting up components..")
            def extraCommand
            try {
                extraCommand = EXTRA_COMMAND
            } catch (Throwable e) {
                extraCommand = null
            }

            if (extraCommand) {
                salt.cmdRun(
                    pepperEnv,
                    targetExpression,
                    extraCommand
                )
            }
            out = salt.cmdRun(
                pepperEnv,
                targetExpression,
                '/bin/bash -c \'find /srv/kubernetes/ -type d | grep -v jobs | while read i; do ls $i/*.yml &>/dev/null && (set -x; hyperkube kubectl apply -f $i || echo Command failed; set +x); done; jobs=$(hyperkube kubectl get jobs -o name); find /srv/kubernetes/jobs -type f -name "*.yml" | while read i; do name=$(grep "name:" $i | head -1 | awk "{print $NF}"); echo $jobs|grep $name >/dev/null || (set -x; hyperkube kubectl apply -f $i || echo Command failed; set +x);done\''
            )
            for (entry in out['return']) {
                for (node in entry) {
                    if (node.value =~ /Command failed/) {
                        error("$node.key: $node.value")
                    } else {
                        println "$node.key: $node.value"
                    }
                }
            }
        }
    }
}
