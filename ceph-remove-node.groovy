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

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def ceph = new com.mirantis.mk.Ceph()
def python = new com.mirantis.mk.Python()
def pepperEnv = "pepperEnv"

def cleanDisk = CLEANDISK

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

        def checknode = salt.runSaltProcessStep(pepperEnv, HOST, 'test.ping')
        if (checknode['return'][0].values().isEmpty()) {
            common.errorMsg("Host not found")
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

            stage('Purge Ceph RGW pkgs') {
                salt.runSaltProcessStep(pepperEnv, HOST, 'pkg.purge', 'ceph-common,libcephfs2,python-cephfs,radosgw,python-rados,python-rbd,python-rgw')
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
        }
        else if (HOST_TYPE.toLowerCase() == 'osd') {
            def osd_ids = []
            def device_grain_name =  "ceph_disk"
            // get list of osd disks of the host
            salt.runSaltProcessStep(pepperEnv, HOST, 'saltutil.sync_grains', [], null, true, 5)
            def ceph_disks = salt.getGrain(pepperEnv, HOST, 'ceph')['return'][0].values()[0].values()[0][device_grain_name]

            for (i in ceph_disks) {
                def osd_id = i.getKey().toString()
                osd_ids.add('osd.' + osd_id)
                print("Will delete " + osd_id)
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
                    def ceph_version = salt.getPillar(pepperEnv, HOST, 'ceph:common:ceph_version').get('return')[0].values()[0]

                    if (ceph_version == "luminous") {
                        try {
                            journal_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/journal_uuid")['return'][0].values()[0].split("\n")[0]
                        }
                        catch(Exception e) {
                            common.infoMsg(e)
                        }
                        try {
                            block_db_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/block.db_uuid")['return'][0].values()[0].split("\n")[0]
                        }
                        catch(Exception e) {
                            common.infoMsg(e)
                        }
                        try {
                            block_wal_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/block.wal_uuid")['return'][0].values()[0].split("\n")[0]
                        }
                        catch(Exception e) {
                            common.infoMsg(e)
                        }
                    }
                    else {
                        def volumes = salt.cmdRun(pepperEnv, HOST, "ceph-volume lvm list --format=json", checkResponse=true, batch=null, output=false)
                        volumes = new groovy.json.JsonSlurperClassic().parseText(volumes['return'][0].values()[0])

                        block_db_partition_uuid = volumes[id][0]['tags'].get('ceph.db_uuid')
                        block_wal_partition_uuid = volumes[id][0]['tags'].get('ceph.wal_uuid')
                    }


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

                if (cleanDisk) {
                // remove data / block / lockbox partition `parted /dev/sdj rm 3`
                    stage('Remove data / block / lockbox partition') {
                        def data_partition_uuid = ""
                        def block_partition_uuid = ""
                        def osd_fsid = ""
                        def lvm = ""
                        def lvm_enabled= salt.getPillar(pepperEnv,"I@ceph:osd","ceph:osd:lvm_enabled")['return'].first().containsValue(true)
                        try {
                            osd_fsid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/fsid")['return'][0].values()[0].split("\n")[0]
                            if (lvm_enabled) {
                                lvm = salt.runSaltCommand(pepperEnv, 'local', ['expression': HOST, 'type': 'compound'], 'cmd.run', null, "salt-call lvm.lvdisplay --output json -l quiet")['return'][0].values()[0]
                                lvm = new groovy.json.JsonSlurperClassic().parseText(lvm)
                                lvm["local"].each { lv, params ->
                                    if (params["Logical Volume Name"].contains(osd_fsid)) {
                                        data_partition_uuid = params["Logical Volume Name"].minus("/dev/")
                                    }
                                }
                            } else {
                                data_partition_uuid = osd_fsid
                            }
                        } catch (Exception e) {
                            common.infoMsg(e)
                        }
                        try {
                            block_partition_uuid = salt.cmdRun(pepperEnv, HOST, "cat /var/lib/ceph/osd/ceph-${id}/block_uuid")['return'][0].values()[0].split("\n")[0]
                        }
                        catch (Exception e) {
                            common.infoMsg(e)
                        }

                        // remove partition_uuid = 2c76f144-f412-481e-b150-4046212ca932
                        if (block_partition_uuid?.trim()) {
                            ceph.removePartition(pepperEnv, HOST, block_partition_uuid)
                            try {
                                salt.cmdRun(pepperEnv, HOST, "ceph-volume lvm zap `readlink /var/lib/ceph/osd/ceph-${id}/block` --destroy")
                            }
                            catch (Exception e) {
                                common.infoMsg(e)
                            }
                        }
                        if (data_partition_uuid?.trim()) {
                            ceph.removePartition(pepperEnv, HOST, data_partition_uuid, 'data', id)
                        }
                    }
                }
            }

            // purge Ceph pkgs
            stage('Purge Ceph OSD pkgs') {
                salt.runSaltProcessStep(pepperEnv, HOST, 'pkg.purge', 'ceph-base,ceph-common,ceph-fuse,ceph-mds,ceph-osd,python-cephfs,librados2,python-rados,python-rbd,python-rgw')
            }

            stage('Remove OSD host from crushmap') {
                def hostname = salt.cmdRun(pepperEnv, HOST, "hostname -s")['return'][0].values()[0].split('\n')[0]
                try {
                    salt.cmdRun(pepperEnv, ADMIN_HOST, "ceph osd crush remove ${hostname}")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
            }

            // stop salt-minion service and move its configuration
            stage('Stop salt-minion') {
                salt.cmdRun(pepperEnv, HOST, "mv /etc/salt/minion.d/minion.conf minion.conf")
                salt.runSaltProcessStep(pepperEnv, HOST, 'service.stop', ['salt-minion'], [], null, true, 5)
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
        }

        if (HOST_TYPE.toLowerCase() == 'mon') {
            // Update Monmap
            stage('Update monmap') {
                salt.cmdRun(pepperEnv, 'I@ceph:mon', "ceph mon getmap -o monmap.backup")
                try {
                    salt.cmdRun(pepperEnv, 'I@ceph:mon', "ceph mon remove ${target}")
                } catch (Exception e) {
                    common.warningMsg(e)
                }
                salt.cmdRun(pepperEnv, 'I@ceph:mon', "monmaptool /tmp/monmap --rm ${target}")
            }

            def target_hosts = salt.getMinions(pepperEnv, 'I@ceph:common')

            // Update configs
            stage('Update Ceph configs') {
                for (tgt in target_hosts) {
                    salt.enforceState(pepperEnv, tgt, 'ceph.common', true)
                }
            }

            stage('Purge Ceph MON pkgs') {
                salt.runSaltProcessStep(pepperEnv, HOST, 'pkg.purge', 'ceph-base,ceph-common,ceph-mgr,ceph-mon,libcephfs2,python-cephfs,python-rbd,python-rgw')
            }
        }

        def crushmap_target = salt.getMinions(pepperEnv, "I@ceph:setup:crush")
        if (HOST_TYPE.toLowerCase() == 'osd' && GENERATE_CRUSHMAP.toBoolean() == true && crushmap_target) {
            stage('Generate CRUSHMAP') {
                salt.enforceState(pepperEnv, 'I@ceph:setup:crush', 'ceph.setup.crush', true)
            }
        }
    }
}
