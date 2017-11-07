/**
 *
 * Remove Ceph node from existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *  HOST                        Host (minion id) to be removed
 *  HOST_TYPE                   Type of Ceph node to be removed. Valid values are mon/osd/rgw
 *  ADMIN_HOST                  Host (minion id) with admin keyring
 *  WAIT_FOR_HEALTHY            Wait for cluster rebalance before stoping daemons
 *  GENERATE_CRUSHMAP           Set to true if the crush map should be generated
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
orchestrate = new com.mirantis.mk.Orchestrate()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"

def runCephCommand(master, target, cmd) {
    return salt.cmdRun(master, target, cmd)
}

def waitForHealthy(master, count=0, attempts=300) {
    // wait for healthy cluster
    while (count<attempts) {
        def health = runCephCommand(master, ADMIN_HOST, 'ceph health')['return'][0].values()[0]
        if (health.contains('HEALTH_OK')) {
            common.infoMsg('Cluster is healthy')
            break;
        }
        count++
        sleep(10)
    }
}

node("python") {

    // create connection to salt master
    python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

    matches = ["osd", "mon", "rgw"]
    def found = false
    for (s in matches) {
        if (HOST_TYPE.toLowerCase() == s) {
            found = true
        }
    }

    if (!found) {
            common.errorMsg("No such HOST_TYPE was found. Please insert one of the following types: mon/osd/rgw")
        break
    }

    stage('Refresh_pillar') {
        salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.refresh_pillar', [], null, true, 5)
    }

    //  split minion id on '.' and remove '*'
    def target = HOST.split("\\.")[0].replace("*", "")

    def _pillar = salt.getGrain(pepperEnv, 'I@salt:master', 'domain')
    domain = _pillar['return'][0].values()[0].values()[0]

    if (HOST_TYPE.toLowerCase() == 'rgw') {
        // Remove Ceph rgw
        stage('Remove Ceph RGW') {
            salt.enforceState(pepperEnv, 'I@ceph:radosgw', ['keepalived', 'haproxy'], true)
        }
    }

    if (HOST_TYPE.toLowerCase() != 'osd') {

        // virsh destroy rgw04.deploy-name.local; virsh undefine rgw04.deploy-name.local;
        stage('Destroy VM') {
            _pillar = salt.getGrain(pepperEnv, 'I@salt:control', 'id')
            def kvm01 = _pillar['return'][0].values()[0].values()[0]

            _pillar = salt.getPillar(pepperEnv, "${kvm01}", "salt:control:cluster:internal:node:${target}:provider")
            def targetProvider = _pillar['return'][0].values()[0]

            salt.cmdRun(pepperEnv, "${targetProvider}", "virsh destroy ${target}.${domain}")
            salt.cmdRun(pepperEnv, "${targetProvider}", "virsh undefine ${target}.${domain}")
        }
    } else if (HOST_TYPE.toLowerCase() == 'osd') {
        def osd_ids = []

        // get list of osd disks of the host
        def ceph_disks = salt.getGrain(pepperEnv, HOST, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']

        for (i in ceph_disks) {
            def osd_id = i.getKey().toString()
            osd_ids.add('osd.' + osd_id)
            print("Will delete " + osd_id)
        }

        // `ceph osd out <id> <id>`
        stage('Set OSDs out') {
                runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd out ' + osd_ids.join(' '))
        }

        // wait for healthy cluster
        if (WAIT_FOR_HEALTHY.toBoolean() == true) {
            sleep(5)
            waitForHealthy(pepperEnv)
        }

        // stop osd daemons
        stage('Stop OSD daemons') {
            for (i in osd_ids) {
                salt.runSaltProcessStep(pepperEnv, HOST, 'service.stop', ['ceph-osd@' + i.replaceAll('osd.', '')],  null, true)
            }
        }

        // `ceph osd crush remove osd.2`
        stage('Remove OSDs from CRUSH') {
            for (i in osd_ids) {
                runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd crush remove ' + i)
            }
        }

        // remove keyring `ceph auth del osd.3`
        stage('Remove OSD keyrings from auth') {
            for (i in osd_ids) {
                runCephCommand(pepperEnv, ADMIN_HOST, 'ceph auth del ' + i)
            }
        }

        // remove osd `ceph osd rm osd.3`
        stage('Remove OSDs') {
            for (i in osd_ids) {
                runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd rm ' + i)
            }
        }

        // purge Ceph pkgs
        stage('Purge Ceph OSD pkgs') {
            runCephCommand(pepperEnv, HOST, 'apt purge ceph-base ceph-common ceph-fuse ceph-mds ceph-osd libcephfs2 python-cephfs librados2 python-rados -y')
        }

        // stop salt-minion service and move its configuration
        stage('Stop salt-minion') {
            salt.cmdRun(pepperEnv, HOST, "mv /etc/salt/minion.d/minion.conf minion.conf")
            salt.runSaltProcessStep(pepperEnv, HOST, 'service.stop', ['salt-minion'], [], null, true, 5)
        }
    }

    stage('Remove salt-key') {
        try {
            salt.cmdRun(pepperEnv, 'I@salt:master', "salt-key -d ${target}.${domain} -y")
        } catch (Exception e) {
            common.warningMsg(e)
        }
        try {
            salt.cmdRun(pepperEnv, 'I@salt:master', "rm /srv/salt/reclass/nodes/_generated/${target}.${domain}.yml")
        } catch (Exception e) {
            common.warningMsg(e)
        }
    }

    if (HOST_TYPE.toLowerCase() == 'mon') {
        // Update Monmap
        stage('Update monmap') {
            runCephCommand(pepperEnv, 'I@ceph:mon', "ceph mon getmap -o monmap.backup")
            try {
                runCephCommand(pepperEnv, 'I@ceph:mon', "ceph mon remove ${target}")
            } catch (Exception e) {
                common.warningMsg(e)
            }
            runCephCommand(pepperEnv, 'I@ceph:mon', "monmaptool /tmp/monmap --rm ${target}")
        }

        // Update configs
        stage('Update Ceph configs') {
            salt.enforceState(pepperEnv, 'I@ceph:common', 'ceph.common', true)
        }
    }

    if (HOST_TYPE.toLowerCase() == 'osd' && GENERATE_CRUSHMAP.toBoolean() == true) {
        stage('Generate CRUSHMAP') {
            salt.enforceState(pepperEnv, 'I@ceph:setup:crush', 'ceph.setup.crush', true)
        }
    }
}
