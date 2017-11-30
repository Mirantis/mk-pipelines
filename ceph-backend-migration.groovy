/**
 *
 * Filestore to Bluestore or vice versa backend migration
 *
 * Requred parameters:
 *  SALT_MASTER_URL                 URL of Salt master
 *  SALT_MASTER_CREDENTIALS         Credentials to the Salt API
 *
 *  ADMIN_HOST                      Host (minion id) with admin keyring and /etc/crushmap file present
 *  OSD                             OSD ids to be migrated if single OSD host is targeted (comma-separated list - 1,2,3)
 *  TARGET                          Hosts (minion ids) to be targeted
 *  CLUSTER_FLAGS                   Comma separated list of tags to apply to cluster
 *  WAIT_FOR_HEALTHY                Wait for cluster rebalance before stoping daemons
 *  ORIGIN_BACKEND                  Ceph backend before upgrade
 *  PER_OSD_CONTROL                 Set to true if Ceph status verification after every osd disk migration is desired
 *  PER_OSD_HOST_CONTROL            Set to true if Ceph status verificaton after whole OSD host migration is desired
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

MIGRATION_METHOD = "per-osd"
// TBD: per-host

def pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS.tokenize(',')
def osds = OSD.tokenize(',')

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

def removeJournalOrBlockPartitions(master, target, id) {

    // remove journal, block_db, block_wal partition `parted /dev/sdj rm 3`
    stage('Remove journal / block_db / block_wal partition') {
        def partition_uuid = ""
        def journal_partition_uuid = ""
        def block_db_partition_uuid = ""
        def block_wal_partition_uuid = ""
        try {
            journal_partition_uuid = runCephCommand(master, target, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep journal | grep partuuid")
            journal_partition_uuid = journal_partition_uuid.toString().trim().split("\n")[0].substring(journal_partition_uuid.toString().trim().lastIndexOf("/")+1)
        } catch (Exception e) {
            common.infoMsg(e)
        }
        try {
            block_db_partition_uuid = runCephCommand(master, target, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep 'block.db' | grep partuuid")
            block_db_partition_uuid = block_db_partition_uuid.toString().trim().split("\n")[0].substring(block_db_partition_uuid.toString().trim().lastIndexOf("/")+1)
        } catch (Exception e) {
            common.infoMsg(e)
        }

        try {
            block_wal_partition_uuid = runCephCommand(master, target, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep 'block.wal' | grep partuuid")
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
            removePartition(master, target, partition_uuid)
        }
        if (block_wal_partition_uuid?.trim()) {
            removePartition(master, target, block_wal_partition_uuid)
        }
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

node("python") {

    // create connection to salt master
    python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

    if (MIGRATION_METHOD == 'per-osd') {

        if (flags.size() > 0) {
            stage('Set cluster flags') {
                for (flag in flags) {
                    runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd set ' + flag)
                }
            }
        }

        def target_hosts = salt.getMinions(pepperEnv, TARGET)

        for (tgt in target_hosts) {
            def osd_ids = []

            // get list of osd disks of the tgt
            salt.runSaltProcessStep(pepperEnv, tgt, 'saltutil.sync_grains', [], null, true, 5)
            def ceph_disks = salt.getGrain(pepperEnv, tgt, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']

            for (i in ceph_disks) {
                def osd_id = i.getKey().toString()
                if (osd_id in osds || OSD == '*') {
                    osd_ids.add('osd.' + osd_id)
                    print("Will migrate " + osd_id)
                } else {
                    print("Skipping " + osd_id)
                }
            }

            for (osd_id in osd_ids) {

                def id = osd_id.replaceAll('osd.', '')
                def backend = runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd metadata ${id} | grep osd_objectstore")['return'][0].values()[0]

                if (backend.contains(ORIGIN_BACKEND.toLowerCase())) {

                    // wait for healthy cluster before manipulating with osds
                    if (WAIT_FOR_HEALTHY.toBoolean() == true) {
                        waitForHealthy(pepperEnv)
                    }

                    // `ceph osd out <id> <id>`
                    stage('Set OSDs out') {
                            runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd out ${osd_id}")
                    }

                    if (WAIT_FOR_HEALTHY.toBoolean() == true) {
                        sleep(5)
                        waitForHealthy(pepperEnv)
                    }

                    // stop osd daemons
                    stage('Stop OSD daemons') {
                        salt.runSaltProcessStep(pepperEnv, tgt, 'service.stop', ['ceph-osd@' + osd_id.replaceAll('osd.', '')],  null, true)
                    }

                    // remove keyring `ceph auth del osd.3`
                    stage('Remove OSD keyrings from auth') {
                        runCephCommand(pepperEnv, ADMIN_HOST, 'ceph auth del ' + osd_id)
                    }

                    // remove osd `ceph osd rm osd.3`
                    stage('Remove OSDs') {
                        runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd rm ' + osd_id)
                    }

                    def dmcrypt = ""
                    try {
                        dmcrypt = runCephCommand(pepperEnv, tgt, "ls -la /var/lib/ceph/osd/ceph-${id}/ | grep dmcrypt")['return'][0].values()[0]
                    } catch (Exception e) {
                        common.warningMsg(e)
                    }

                    if (dmcrypt?.trim()) {
                        def mount = runCephCommand(pepperEnv, tgt, "lsblk -rp | grep /var/lib/ceph/osd/ceph-${id} -B1")['return'][0].values()[0]
                        dev = mount.split()[0].replaceAll("[0-9]","")

                        // remove partition tables
                        stage('dd part tables') {
                            runCephCommand(pepperEnv, tgt, "dd if=/dev/zero of=${dev} bs=512 count=1 conv=notrunc")
                        }

                        // remove journal, block_db, block_wal partition `parted /dev/sdj rm 3`
                        removeJournalOrBlockPartitions(pepperEnv, tgt, id)

                        // reboot
                        stage('reboot and wait') {
                            salt.runSaltProcessStep(pepperEnv, tgt, 'system.reboot', null, null, true, 5)
                            salt.minionsReachable(pepperEnv, 'I@salt:master', tgt)
                            sleep(10)
                        }

                        // zap disks `ceph-disk zap /dev/sdi`
                        stage('Zap devices') {
                            try {
                                runCephCommand(pepperEnv, tgt, 'ceph-disk zap ' + dev)
                            } catch (Exception e) {
                                common.warningMsg(e)
                            }
                            runCephCommand(pepperEnv, tgt, 'ceph-disk zap ' + dev)
                        }

                    } else {

                        def mount = runCephCommand(pepperEnv, tgt, "mount | grep /var/lib/ceph/osd/ceph-${id}")['return'][0].values()[0]
                        dev = mount.split()[0].replaceAll("[0-9]","")

                        // remove journal, block_db, block_wal partition `parted /dev/sdj rm 3`
                        removeJournalOrBlockPartitions(pepperEnv, tgt, id)

                        // umount `umount /dev/sdi1`
                        stage('Umount devices') {
                            runCephCommand(pepperEnv, tgt, "umount /var/lib/ceph/osd/ceph-${id}")
                        }

                        // zap disks `ceph-disk zap /dev/sdi`
                        stage('Zap device') {
                            runCephCommand(pepperEnv, tgt, 'ceph-disk zap ' + dev)
                        }
                    }

                    // Deploy Ceph OSD
                    stage('Deploy Ceph OSD') {
                        salt.runSaltProcessStep(pepperEnv, tgt, 'saltutil.refresh_pillar', [], null, true, 5)
                        salt.enforceState(pepperEnv, tgt, 'ceph.osd', true)
                    }

                    if (PER_OSD_CONTROL.toBoolean() == true) {
                        stage("Verify backend version for osd.${id}") {
                            sleep(5)
                            runCephCommand(pepperEnv, tgt, "ceph osd metadata ${id} | grep osd_objectstore")
                            runCephCommand(pepperEnv, tgt, "ceph -s")
                        }

                        stage('Ask for manual confirmation') {
                            input message: "From the verification commands above, please check the backend version of osd.${id} and ceph status. If it is correct, Do you want to continue to migrate next osd?"
                        }
                    }
                }
            }
            if (PER_OSD_HOST_CONTROL.toBoolean() == true) {
                stage("Verify backend versions") {
                    sleep(5)
                    runCephCommand(pepperEnv, tgt, "ceph osd metadata | grep osd_objectstore -B2")
                    runCephCommand(pepperEnv, tgt, "ceph -s")
                }

                stage('Ask for manual confirmation') {
                    input message: "From the verification command above, please check the ceph status and backend version of osds on this host. If it is correct, Do you want to continue to migrate next OSD host?"
                }
            }

        }
        // remove cluster flags
        if (flags.size() > 0) {
            stage('Unset cluster flags') {
                for (flag in flags) {
                    common.infoMsg('Removing flag ' + flag)
                    runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd unset ' + flag)
                }
            }
        }
    }
}
