def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()

targetExpression = TARGET_MINIONS ? TARGET_MINIONS : "E@kvm01.*"

node() {
    def saltMaster
    stage('Connect to Salt master') {
        saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }
    common.infoMsg("Enforcing kubernetes state..")
    stage("Update k8s control") {
        salt.enforceState(
            saltMaster,
            targetExpression,
            'kubernetes.control',
            true
        )
    }
    stage("Update components") {
        common.infoMsg("Setting up components..")
        out = salt.cmdRun(
            saltMaster,
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
