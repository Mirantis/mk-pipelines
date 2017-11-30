/**
 *
 * Replace failed disk with a new disk
 *
 * Requred parameters:
 *  SALT_MASTER_URL                     URL of Salt master
 *  SALT_MASTER_CREDENTIALS             Credentials to the Salt API
 *
 *  HOST                                Host (minion id) to be removed
 *  ADMIN_HOST                          Host (minion id) with admin keyring and /etc/crushmap file present
 *  OSD                                 Failed OSD ids to be replaced (comma-separated list - 1,2,3)
 *  DEVICE                              Comma separated list of failed devices that will be replaced at HOST (/dev/sdb,/dev/sdc)
 *  JOURNAL_BLOCKDB_BLOCKWAL_PARTITION  Comma separated list of partitions where journal or block_db or block_wal for the failed devices on this HOST were stored (/dev/sdh2,/dev/sdh3)
 *  CLUSTER_FLAGS                       Comma separated list of tags to apply to cluster
 *  WAIT_FOR_HEALTHY                    Wait for cluster rebalance before stoping daemons
 *  DMCRYPT                             Set to True if replacing osds are/were encrypted
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS.tokenize(',')
def osds = OSD.tokenize(',')
def devices = DEVICE.tokenize(',')
def journals_blockdbs_blockwals = JOURNAL_BLOCKDB_BLOCKWAL_PARTITION.tokenize(',')


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

    def osd_ids = []

    for (osd_id in osds) {
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


    if (flags.size() > 0) {
        stage('Set cluster flags') {
            for (flag in flags) {
                runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd set ' + flag)
            }
        }
    }

    // stop osd daemons
    stage('Stop OSD daemons') {
        for (i in osd_ids) {
            salt.runSaltProcessStep(pepperEnv, HOST, 'service.stop', ['ceph-osd@' + i.replaceAll('osd.', '')],  null, true)
        }
    }
    /*
    // `ceph osd crush remove osd.2`
    stage('Remove OSDs from CRUSH') {
        for (i in osd_ids) {
            runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd crush remove ' + i)
        }
    }

    // wait for pgs to rebalance
    if (WAIT_FOR_PG_REBALANCE.toBoolean() == true) {
        stage('Waiting for pgs to rebalance') {
            while (true) {
                def status = runCephCommand(pepperEnv, ADMIN_HOST, 'ceph -s')['return'][0].values()[0]
                if (!status.contains('degraded')) {
                    common.infoMsg('PGs rebalanced')
                    break;
                }
                sleep(10)
            }
        }
    }
    */
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

    if (DMCRYPT.toBoolean() == true) {

        // remove partition tables
        stage('dd part tables') {
            for (dev in devices) {
                runCephCommand(pepperEnv, HOST, "dd if=/dev/zero of=${dev} bs=512 count=1 conv=notrunc")
            }
        }

        // remove journal, block_db or block_wal partition `parted /dev/sdj rm 3`
        stage('Remove journal / block_db / block_wal partitions') {
            for (partition in journals_blockdbs_blockwals) {
                if (partition?.trim()) {
                    // dev = /dev/sdi
                    def dev = partition.replaceAll("[0-9]", "")
                    // part_id = 2
                    def part_id = partition.substring(partition.lastIndexOf("/")+1).replaceAll("[^0-9]", "")
                    try {
                        runCephCommand(pepperEnv, HOST, "Ignore | parted ${dev} rm ${part_id}")
                    } catch (Exception e) {
                        common.warningMsg(e)
                    }
                }
            }
        }

        // reboot
        stage('reboot and wait') {
            salt.runSaltProcessStep(pepperEnv, HOST, 'system.reboot', null, null, true, 5)
            salt.minionsReachable(pepperEnv, 'I@salt:master', HOST)
            sleep(10)
        }



        // zap disks `ceph-disk zap /dev/sdi`
        stage('Zap devices') {
            for (dev in devices) {
                try {
                    runCephCommand(pepperEnv, HOST, 'ceph-disk zap ' + dev)
                } catch (Exception e) {
                    common.warningMsg(e)
                }
                runCephCommand(pepperEnv, HOST, 'ceph-disk zap ' + dev)
            }
        }

    } else {

        // umount `umount /dev/sdi1`
        stage('Umount devices') {
            for (dev in devices) {
                runCephCommand(pepperEnv, HOST, 'umount ' + dev + '1')
            }
        }

        // zap disks `ceph-disk zap /dev/sdi`
        stage('Zap devices') {
            for (dev in devices) {
                runCephCommand(pepperEnv, HOST, 'ceph-disk zap ' + dev)
            }
        }

        // remove journal, block_db or block_wal partition `parted /dev/sdj rm 3`
        stage('Remove journal / block_db / block_wal partitions') {
            for (partition in journals_blockdbs_blockwals) {
                if (partition?.trim()) {
                    // dev = /dev/sdi
                    def dev = partition.replaceAll("[0-9]", "")
                    // part_id = 2
                    def part_id = partition.substring(partition.lastIndexOf("/")+1).replaceAll("[^0-9]", "")
                    try {
                        runCephCommand(pepperEnv, HOST, "parted ${dev} rm ${part_id}")
                    } catch (Exception e) {
                        common.warningMsg(e)
                    }
                }
            }
        }
    }

    // Deploy failed Ceph OSD
    stage('Deploy Ceph OSD') {
        salt.enforceState(pepperEnv, HOST, 'ceph.osd', true)
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

    /*
    if (ENFORCE_CRUSHMAP.toBoolean() == true) {

        // enforce crushmap `crushtool -c /etc/ceph/crushmap -o /etc/ceph/crushmap.compiled; ceph osd setcrushmap -i /etc/ceph/crushmap.compiled`
        stage('Enforce crushmap') {

            stage('Ask for manual confirmation') {
                input message: "Are you sure that your ADMIN_HOST has correct /etc/ceph/crushmap file? Click proceed to compile and enforce crushmap."
            }
            runCephCommand(pepperEnv, ADMIN_HOST, 'crushtool -c /etc/ceph/crushmap -o /etc/ceph/crushmap.compiled')
            runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd setcrushmap -i /etc/ceph/crushmap.compiled')
        }
    }
    */
}
