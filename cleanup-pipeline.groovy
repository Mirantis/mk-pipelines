/**
 *
 * Delete heat stack pipeline
 *
 * General
 *  STACK_NAME                 Heat stack name
 *  STACK_TYPE                 Type of the stack (heat, aws)
 *
 * Heat parameters:
 *  OPENSTACK_API_URL            OpenStack API address
 *  OPENSTACK_API_CREDENTIALS    Credentials to the OpenStack API
 *  OPENSTACK_API_PROJECT        OpenStack project to connect to
 *  OPENSTACK_API_PROJECT_DOMAIN Domain for OpenStack project
 *  OPENSTACK_API_PROJECT_ID     ID for OpenStack project
 *  OPENSTACK_API_USER_DOMAIN    Domain for OpenStack user
 *  OPENSTACK_API_CLIENT         Versions of OpenStack python clients
 *  OPENSTACK_API_VERSION        Version of the OpenStack API (2/3)
 *
 * AWS parameters:
 *  AWS_API_CREDENTIALS        Credentials id AWS EC2 API
 *  AWS_DEFAULT_REGION         EC2 region
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
aws = new com.mirantis.mk.Aws()
salt = new com.mirantis.mk.Salt()

node {

    def venv_path = "${env.WORKSPACE}/venv"
    def env_vars

    // default STACK_TYPE is heat
    if (!env.getEnvironment().containsKey("STACK_TYPE") || STACK_TYPE == '') {
        STACK_TYPE = 'heat'
    }

    stage('Install environment') {
        if (STACK_TYPE == 'heat') {

            def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
            openstack.setupOpenstackVirtualenv(venv_path, openstackVersion)

        } else if (STACK_TYPE == 'aws') {

            env_vars = aws.getEnvVars(AWS_API_CREDENTIALS, AWS_DEFAULT_REGION)
            aws.setupVirtualEnv(venv_path)

        } else {
            throw new Exception('Stack type is not supported')
        }

    }

    stage('Delete stack') {
        if (STACK_TYPE == 'heat') {
            def openstackCloud = openstack.createOpenstackEnv(
                OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
                OPENSTACK_API_PROJECT,OPENSTACK_API_PROJECT_DOMAIN,
                OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
                OPENSTACK_API_VERSION)
            openstack.getKeystoneToken(openstackCloud, venv_path)

            common.infoMsg("Deleting Heat Stack " + STACK_NAME)
            openstack.deleteHeatStack(openstackCloud, STACK_NAME, venv_path)
        } else if (STACK_TYPE == 'aws') {

            aws.deleteStack(venv_path, env_vars, STACK_NAME)
            aws.waitForStatus(venv_path, env_vars, STACK_NAME, 'DELETE_COMPLETE', ['DELETE_FAILED'])

        } else {
            throw new Exception('Stack type is not supported')
        }

    }

}
