/**
 *
 * Remove OSD from existing cluster
 *
 * Requred parameters:
 *  SALT_MASTER_URL             URL of Salt master
 *  SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *
 *  HOST                        Host (minion id) to be removed
 *  OSD                         Comma separated list of osd ids to be removed
 *  ADMIN_HOST                  Host (minion id) with admin keyring
 *  CLUSTER_FLAGS               Comma separated list of tags to apply to cluster
 *  WAIT_FOR_HEALTHY            Wait for cluster rebalance before stoping daemons
 *
 */

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()

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

    if (flags.size() > 0) {
        stage('Set cluster flags') {
            for (flag in flags) {
                runCephCommand(pepperEnv, ADMIN_HOST, 'ceph osd set ' + flag)
            }
        }
    }

    def osd_ids = []

    // get list of osd disks of the host
    salt.runSaltProcessStep(pepperEnv, HOST, 'saltutil.sync_grains', [], null, true, 5)
    def ceph_disks = salt.getGrain(pepperEnv, HOST, 'ceph')['return'][0].values()[0].values()[0]['ceph_disk']
    common.prettyPrint(ceph_disks)

    for (i in ceph_disks) {
        def osd_id = i.getKey().toString()
        if (osd_id in osds || OSD == '*') {
            osd_ids.add('osd.' + osd_id)
            print("Will delete " + osd_id)
        } else {
            print("Skipping " + osd_id)
        }
    }

    // wait for healthy cluster
    if (WAIT_FOR_HEALTHY.toBoolean() == true) {
        waitForHealthy(pepperEnv)
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
