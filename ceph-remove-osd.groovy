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

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def ceph = new com.mirantis.mk.Ceph()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def flags = CLUSTER_FLAGS.tokenize(',')
def osds = OSD.tokenize(',')

timeout(time: 12, unit: 'HOURS') {
    node("python") {

        // create connection to salt master
        python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        if (flags.size() > 0) {
            stage('Set cluster flags') {
                for (flag in flags) {
                    salt.cmdRun(pepperEnv, ADMIN_HOST, 'ceph osd set ' + flag)
                }
            }
        }

        def osd_ids = []

        // get list of osd disks of the host
        salt.runSaltProcessStep(pepperEnv, HOST, 'saltutil.sync_grains', [], null, true, 5)
        def cephGrain = salt.getGrain(pepperEnv, HOST, 'ceph')

        if (cephGrain['return'].isEmpty()) {
            throw new Exception("Ceph salt grain cannot be found!")
        }
        common.print(cephGrain)
        def ceph_disks = cephGrain['return'][0].values()[0].values()[0]['ceph_disk']
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

        if (osd_ids == []) {
            currentBuild.result = 'SUCCESS'
            return
        }

        // `ceph osd out <id> <id>`
        stage('Set OSDs out') {
            salt.cmdRun(pepperEnv, ADMIN_HOST, 'ceph osd out ' + osd_ids.join(' '))
        }

        // wait for healthy cluster
        if (WAIT_FOR_HEALTHY.toBoolean()) {
            sleep(5)
            ceph.waitForHealthy(pepperEnv, ADMIN_HOST)
        }

        // stop osd daemons
        stage('Stop OSD daemons') {
            for (i in osd_ids) {
                salt.runSaltProcessStep(pepperEnv, HOST, 'service.stop', ['ceph-osd@' + i.replaceAll('osd.', '')], null, true)
            }
        }

        // `ceph osd crush remove osd.2`
        stage('Remove OSDs from CRUSH') {
            for (i in osd_ids) {
                salt.cmdRun(pepperEnv, ADMIN_HOST, 'ceph osd crush remove ' + i)
            }
        }

        // remove keyring `ceph auth del osd.3`
        stage('Remove OSD keyrings from auth') {
            for (i in osd_ids) {
                salt.cmdRun(pepperEnv, ADMIN_HOST, 'ceph auth del ' + i)
            }
        }

        // remove osd `ceph osd rm osd.3`
        stage('Remove OSDs') {
            for (i in osd_ids) {
                salt.cmdRun(pepperEnv, ADMIN_HOST, 'ceph osd rm ' + i)
            }
        }

        for (osd_id in osd_ids) {
            id = osd_id.replaceAll('osd.', '')

            // remove journal, block_db, block_wal partition `parted /dev/sdj rm 3`
            stage('Remove journal / block_db / block_wal partition') {
                def partition_uuid = ""
                def journal_partition_uuid = ""
                def block_db_partition_uuid = ""
                def block_wal_partition_uuid = ""
                try {
                    journal_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/journal_uuid")['return'][0].values()[0].split("\n")[0]
                } catch (Exception e) {
                    common.infoMsg(e)
                }
                try {
                    block_db_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/block.db_uuid")['return'][0].values()[0].split("\n")[0]
                } catch (Exception e) {
                    common.infoMsg(e)
                }

                try {
                    block_wal_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/block.wal_uuid")['return'][0].values()[0].split("\n")[0]
                } catch (Exception e) {
                    common.infoMsg(e)
                }

                // remove partition_uuid = 2c76f144-f412-481e-b150-4046212ca932
                if (journal_partition_uuid?.trim()) {
                    ceph.removePartition(pepperEnv, HOST, journal_partition_uuid)
                }
                if (block_db_partition_uuid?.trim()) {
                    ceph.removePartition(pepperEnv, HOST, block_db_partition_uuid)
                }
                if (block_wal_partition_uuid?.trim()) {
                    ceph.removePartition(pepperEnv, HOST, block_wal_partition_uuid)
                }

                try {
                    salt.cmdRun(pepperEnv, HOST, "partprobe")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
            }

            // remove data / block / lockbox partition `parted /dev/sdj rm 3`
            stage('Remove data / block / lockbox partition') {
                def data_partition_uuid = ""
                def block_partition_uuid = ""
                def lockbox_partition_uuid = ""
                try {
                    data_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/fsid")['return'][0].values()[0].split("\n")[0]
                    common.print(data_partition_uuid)
                } catch (Exception e) {
                    common.infoMsg(e)
                }
                try {
                    block_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/block_uuid")['return'][0].values()[0].split("\n")[0]
                } catch (Exception e) {
                    common.infoMsg(e)
                }

                try {
                    lockbox_partition_uuid = data_partition_uuid
                } catch (Exception e) {
                    common.infoMsg(e)
                }

                // remove partition_uuid = 2c76f144-f412-481e-b150-4046212ca932
                if (block_partition_uuid?.trim()) {
                    ceph.removePartition(pepperEnv, HOST, block_partition_uuid)
                }
                if (data_partition_uuid?.trim()) {
                    ceph.removePartition(pepperEnv, HOST, data_partition_uuid, 'data', id)
                }
                if (lockbox_partition_uuid?.trim()) {
                    ceph.removePartition(pepperEnv, HOST, lockbox_partition_uuid, 'lockbox')
                }
            }
        }
        // remove cluster flags
        if (flags.size() > 0) {
            stage('Unset cluster flags') {
                for (flag in flags) {
                    common.infoMsg('Removing flag ' + flag)
                    salt.cmdRun(pepperEnv, ADMIN_HOST, 'ceph osd unset ' + flag)
                }
            }
        }
    }
}
