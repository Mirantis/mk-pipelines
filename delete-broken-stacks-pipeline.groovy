/**
 *
 * Delete broken heat stacks pipeline (in CREATE_FAILED or DELETE_FAILED state)
 *
 * Expected parameters:
 *   OPENSTACK_API_URL          OpenStack API address
 *   OPENSTACK_API_CREDENTIALS  Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT      OpenStack project to connect to
 *   OPENSTACK_API_CLIENT       Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION      Version of the OpenStack API (2/3)
 *
 *
 */
common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()
timeout(time: 12, unit: 'HOURS') {
    node {

        // connection objects
        def openstackCloud
        // value defaults
        def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
        def openstackEnv = "${env.WORKSPACE}/venv"

        stage('Install OpenStack env') {
            openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
        }

        stage('Connect to OpenStack cloud') {
            openstackCloud = openstack.createOpenstackEnv(OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS, OPENSTACK_API_PROJECT)
            openstack.getKeystoneToken(openstackCloud, openstackEnv)
        }

        stage('Delete broken Heat stacks') {
            // get failed stacks
            def brokenStacks = []
            brokenStacks.addAll(openstack.getStacksWithStatus(openstackCloud, "CREATE_FAILED", openstackEnv))
            brokenStacks.addAll(openstack.getStacksWithStatus(openstackCloud, "DELETE_FAILED", openstackEnv))
            for(int i=0;i<brokenStacks.size();i++){
                common.infoMsg("Deleting Heat stack " + brokenStacks[i])
                openstack.deleteHeatStack(openstackCloud, brokenStacks[i], openstackEnv)
            }
        }

    }
}
