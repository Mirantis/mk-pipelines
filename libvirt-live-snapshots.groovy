/**
 * Control live snapshots
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [http://10.10.10.1:8000].
 *   CREATE_LIVE_SNAPSHOT               Ensures that the live snapshot exists (bool)
 *   ROLLBACK_LIVE_SNAPSHOT             Rollback to a state before live snapshot was taken (bool)
 *   REMOVE_LIVE_SNAPSHOT               Ensures that the live snapshot does not exist (bool)
 *   MERGE_LIVE_SNAPSHOT                Ensures that the live snapshot is merged into it's base image (bool)
 *   NODE_PROVIDER                      KVM node that hosts the VM (for ex. kvm02)
 *   TARGET                             Unique identification of the VM being snapshoted without domain name (for ex. ctl01)
 *   SNAPSHOT_NAME                      Snapshot name
 *   LIBVIRT_IMAGES_PATH                Path where snapshot image and dumpxml are being put
 *   DISK_NAME                          Disk name of the snapshot
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def virsh = new com.mirantis.mk.Virsh()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
timeout(time: 12, unit: 'HOURS') {
    node() {

        stage('Setup virtualenv for Pepper') {
            python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        if (CREATE_LIVE_SNAPSHOT.toBoolean() == true) {
            stage('Create live snapshot') {
                virsh.liveSnapshotPresent(pepperEnv, NODE_PROVIDER, TARGET, SNAPSHOT_NAME, LIBVIRT_IMAGES_PATH, DISK_NAME)
            }
        }

        if (REMOVE_LIVE_SNAPSHOT.toBoolean() == true) {
            stage('Remove live snapshot') {
                virsh.liveSnapshotAbsent(pepperEnv, NODE_PROVIDER, TARGET, SNAPSHOT_NAME, LIBVIRT_IMAGES_PATH)
            }
        }

        if (ROLLBACK_LIVE_SNAPSHOT.toBoolean() == true) {
            stage('Rollback live snapshot') {
                sleep(30)
                virsh.liveSnapshotRollback(pepperEnv, NODE_PROVIDER, TARGET, SNAPSHOT_NAME, LIBVIRT_IMAGES_PATH)
            }
        }

        if (MERGE_LIVE_SNAPSHOT.toBoolean() == true) {
            stage('Merge live snapshot') {
                sleep(30)
                virsh.liveSnapshotMerge(pepperEnv, NODE_PROVIDER, TARGET, SNAPSHOT_NAME, LIBVIRT_IMAGES_PATH, DISK_NAME)
            }
        }
    }
}