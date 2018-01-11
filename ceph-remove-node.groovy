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

def removePartition(master, target, partition_uuid) {
    def partition = ""
    try {
        // partition = /dev/sdi2
        partition = runCephCommand(master, target, "blkid | grep ${partition_uuid} ")['return'][0].values()[0].split("(?<=[0-9])")[0]
    } catch (Exception e) {
        common.warningMsg(e)
    }

    if (partition?.trim()) {
        // dev = /dev/sdi
        def dev = partition.replaceAll('\\d+$', "")
        // part_id = 2
        def part_id = partition.substring(partition.lastIndexOf("/")+1).replaceAll("[^0-9]", "")
        runCephCommand(master, target, "parted ${dev} rm ${part_id}")
    }
    return
}

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
timeout(time: 12, unit: 'HOURS') {
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
            throw new InterruptedException()
        }

        stage('Refresh_pillar') {
            salt.runSaltProcessStep(pepperEnv, '*', 'saltutil.refresh_pillar', [], null, true, 5)
        }

        //  split minion id on '.' and remove '*'
        def target = HOST.split("\\.")[0].replace("*", "")

        salt.runSaltProcessStep(pepperEnv, 'I@salt:master', 'saltutil.sync_grains', [], null, true, 5)
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
            stage('Destroy/Undefine VM') {
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
            salt.runSaltProcessStep(pepperEnv, HOST, 'saltutil.sync_grains', [], null, true, 5)
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

            for (osd_id in osd_ids) {

                id = osd_id.replaceAll('osd.', '')
                def dmcrypt = ""
                try {
                    dmcrypt = runCephCommand(pepperEnv, HOST, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep dmcrypt")['return'][0].values()[0]
                } catch (Exception e) {
                    common.warningMsg(e)
                }

                if (dmcrypt?.trim()) {
                    mount = runCephCommand(pepperEnv, HOST, "lsblk -rp | grep /var/lib/ceph/osd/ceph-${id} -B1")['return'][0].values()[0]
                    dev = mount.split()[0].replaceAll("[0-9]","")

                    // remove partition tables
                    stage("dd part table on ${dev}") {
                        runCephCommand(pepperEnv, HOST, "dd if=/dev/zero of=${dev} bs=512 count=1 conv=notrunc")
                    }

                }
                // remove journal, block_db, block_wal partition `parted /dev/sdj rm 3`
                stage('Remove journal / block_db / block_wal partition') {
                    def partition_uuid = ""
                    def journal_partition_uuid = ""
                    def block_db_partition_uuid = ""
                    def block_wal_partition_uuid = ""
                    try {
                        journal_partition_uuid = runCephCommand(pepperEnv, HOST, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep journal | grep partuuid")
                        journal_partition_uuid = journal_partition_uuid.toString().trim().split("\n")[0].substring(journal_partition_uuid.toString().trim().lastIndexOf("/")+1)
                    } catch (Exception e) {
                        common.infoMsg(e)
                    }
                    try {
                        block_db_partition_uuid = runCephCommand(pepperEnv, HOST, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep 'block.db' | grep partuuid")
                        block_db_partition_uuid = block_db_partition_uuid.toString().trim().split("\n")[0].substring(block_db_partition_uuid.toString().trim().lastIndexOf("/")+1)
                    } catch (Exception e) {
                        common.infoMsg(e)
                    }

                    try {
                        block_wal_partition_uuid = runCephCommand(pepperEnv, HOST, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep 'block.wal' | grep partuuid")
                        block_wal_partition_uuid = block_wal_partition_uuid.toString().trim().split("\n")[0].substring(block_wal_partition_uuid.toString().trim().lastIndexOf("/")+1)
                    } catch (Exception e) {
                        common.infoMsg(e)
                    }

                    // set partition_uuid = 2c76f144-f412-481e-b150-4046212ca932
                    if (journal_partition_uuid?.trim()) {
                        partition_uuid = journal_partition_uuid
                    } else if (block_db_partition_uuid?.trim()) {
                        partition_uuid = block_db_partition_uuid
                    }

                    // if disk has journal, block_db or block_wal on different disk, then remove the partition
                    if (partition_uuid?.trim()) {
                        removePartition(pepperEnv, HOST, partition_uuid)
                    }
                    if (block_wal_partition_uuid?.trim()) {
                        removePartition(pepperEnv, HOST, block_wal_partition_uuid)
                    }
                }
            }

            // purge Ceph pkgs
            stage('Purge Ceph OSD pkgs') {
                runCephCommand(pepperEnv, HOST, 'apt purge ceph-base ceph-common ceph-fuse ceph-mds ceph-osd python-cephfs librados2 python-rados -y')
            }

            stage('Remove OSD host from crushmap') {
                def hostname = runCephCommand(pepperEnv, HOST, "hostname -s")['return'][0].values()[0].split('\n')[0]
                try {
                    runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd crush remove ${hostname}")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
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

        stage('Remove keyring') {
            def keyring = ""
            def keyring_lines = ""
            try {
                keyring_lines = runCephCommand(pepperEnv, ADMIN_HOST, "ceph auth list | grep ${target}")['return'][0].values()[0].split('\n')
            } catch (Exception e) {
                common.warningMsg(e)
            }
            for (line in keyring_lines) {
                if (line.toLowerCase().contains(target.toLowerCase())) {
                    keyring = line
                    break
                }
            }
            if (keyring?.trim()) {
                runCephCommand(pepperEnv, ADMIN_HOST, "ceph auth del ${keyring}")
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

            def target_hosts = salt.getMinions(pepperEnv, 'I@ceph:common')
            print target_hosts

            // Update configs
            stage('Update Ceph configs') {
                for (tgt in target_hosts) {
                    salt.enforceState(pepperEnv, tgt, 'ceph.common', true)
                }
            }
        }

        if (HOST_TYPE.toLowerCase() == 'osd' && GENERATE_CRUSHMAP.toBoolean() == true) {
            stage('Generate CRUSHMAP') {
                salt.enforceState(pepperEnv, 'I@ceph:setup:crush', 'ceph.setup.crush', true)
            }
        }
    }
}
