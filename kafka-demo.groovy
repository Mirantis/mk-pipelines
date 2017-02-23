/**
 *
 * Run states on K8s
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials used to access Salt API
 *   SALT_URL                   URL usedd to connect to Salt API
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
orchestrate = new com.mirantis.mk.Orchestrate()

node {

    // connection objects
    def master

    stage("Connect to Salt master") {
        master = salt.connection(SALT_URL, SALT_MASTER_CREDENTIALS)
    }


    stage("Enforce kubernetes.control") {
        common.infoMsg('Enforcing kubernetes.control on I@kubernetes:master')

        salt.runSaltProcessStep(
            master,
            'I@kubernetes:master',
            'state.sls',
            ['kubernetes.control'],
        )
    }

    stage("setup-components") {
        common.infoMsg('Setting up components')

        salt.runSaltProcessStep(
            master,
            'I@kubernetes:master',
            'cmd.run',
            ['/bin/bash -c \'find /srv/kubernetes/ -type d | grep -v jobs | while read i; do ls $i/*.yml &>/dev/null && (set -x; hyperkube kubectl apply -f $i || echo Command failed; set +x); done; jobs=$(hyperkube kubectl get jobs -o name); find /srv/kubernetes/jobs -type f -name "*.yml" | while read i; do name=$(grep "name:" $i | head -1 | awk "{print $NF}"); echo $jobs|grep $name >/dev/null || (set -x; hyperkube kubectl apply -f $i || echo Command failed; set +x);done\'']
        )

    }

}
