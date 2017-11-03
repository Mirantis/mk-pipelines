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

def runCephCommand(master, target, cmd) {
    return salt.cmdRun(master, target, cmd)
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

        for (HOST in target_hosts) {
            def osd_ids = []

            // get list of osd disks of the host
            def ceph_disks = salt.getGrain(pepperEnv, HOST, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']

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

                if (backend.contains(ORIGIN_BACKEND)) {

                    // wait for healthy cluster before manipulating with osds
                    if (WAIT_FOR_HEALTHY.toBoolean() == true) {
                        stage('Waiting for healthy cluster') {
                            while (true) {
                                def health = runCephCommand(pepperEnv, ADMIN_HOST, 'ceph health')['return'][0].values()[0]
                                if (health.contains('HEALTH_OK')) {
                                    common.infoMsg('Cluster is healthy')
                                    break;
                                }
                                sleep(5)
                            }
                        }
                    }

                    // `ceph osd out <id> <id>`
                    stage('Set OSDs out') {
                            runCephCommand(pepperEnv, ADMIN_HOST, "ceph osd out ${osd_id}")
                    }

                    // wait for healthy cluster
                    if (WAIT_FOR_HEALTHY.toBoolean() == true) {
                        stage('Waiting for healthy cluster') {
                            sleep(5)
                            while (true) {
                                def health = runCephCommand(pepperEnv, ADMIN_HOST, 'ceph health')['return'][0].values()[0]
                                if (health.contains('HEALTH_OK')) {
                                    common.infoMsg('Cluster is healthy')
                                    break;
                                }
                                sleep(10)
                            }
                        }
                    }

                    // stop osd daemons
                    stage('Stop OSD daemons') {
                        salt.runSaltProcessStep(pepperEnv, HOST, 'service.stop', ['ceph-osd@' + osd_id.replaceAll('osd.', '')],  null, true)
                    }

                    // remove keyring `ceph auth del osd.3`
                    stage('Remove OSD keyrings from auth') {
                        runCephCommand(pepperEnv, ADMIN_HOST, 'ceph auth del ' + osd_id)
                    }

                    // remove osd `ceph osd rm osd.3`
                    stage('Remove OSDs') {
                        runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd rm ' + osd_id)
                    }

                    def mount = runCephCommand(pepperEnv, HOST, "mount | grep /var/lib/ceph/osd/ceph-${id}")['return'][0].values()[0]
                    dev = mount.split()[0].replaceAll("[0-9]","")

                    // remove journal or block_db partition `parted /dev/sdj rm 3`
                    stage('Remove journal / block_db partition') {
                        def partition_uuid = ""
                        def journal_partition_uuid = ""
                        def block_db_partition_uuid = ""
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

                        // set partition_uuid = 2c76f144-f412-481e-b150-4046212ca932
                        if (journal_partition_uuid?.trim()) {
                            partition_uuid = journal_partition_uuid
                        } else if (block_db_partition_uuid?.trim()) {
                            partition_uuid = block_db_partition_uuid
                        }

                        // if failed disk had block_db or journal on different disk, then remove the partition
                        if (partition_uuid?.trim()) {
                            def partition = ""
                            try {
                                // partition = /dev/sdi2
                                partition = runCephCommand(pepperEnv, HOST, "blkid | grep ${partition_uuid} ")['return'][0].values()[0].split("(?<=[0-9])")[0]
                            } catch (Exception e) {
                                common.warningMsg(e)
                            }

                            if (partition?.trim()) {
                                // dev = /dev/sdi
                                def dev = partition.replaceAll("[0-9]", "")
                                // part_id = 2
                                def part_id = partition.substring(partition.lastIndexOf("/")+1).replaceAll("[^0-9]", "")
                                runCephCommand(pepperEnv, HOST, "parted ${dev} rm ${part_id}")
                            }
                        }
                    }

                    // umount `umount /dev/sdi1`
                    stage('Umount devices') {
                        runCephCommand(pepperEnv, HOST, "umount /var/lib/ceph/osd/ceph-${id}")
                    }

                    // zap disks `ceph-disk zap /dev/sdi`
                    stage('Zap device') {
                        runCephCommand(pepperEnv, HOST, 'ceph-disk zap ' + dev)
                    }

                    // Deploy failed Ceph OSD
                    stage('Deploy Ceph OSD') {
                        salt.runSaltProcessStep(pepperEnv, HOST, 'saltutil.refresh_pillar', [], null, true, 5)
                        salt.enforceState(pepperEnv, HOST, 'ceph.osd', true)
                    }
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
