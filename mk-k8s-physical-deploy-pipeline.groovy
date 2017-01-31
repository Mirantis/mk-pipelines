/**
 *
 * Launch heat stack with basic k8s
 *
 * Expected parameters:
 *   SALT_MASTER_URL            URL used for connection to Salt master
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 */

salt = new com.mirantis.mk.salt()

node {

    // connection objects
    def saltMaster

    stage("Connect to Salt master") {
        saltMaster = salt.createSaltConnection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }

    //stage("Install core infra") {
    //    salt.installFoundationInfra(saltMaster)
    //    salt.validateFoundationInfra(saltMaster)
    //}

    //stage("Install Kubernetes infra") {
    //    salt.installOpenstackMcpInfra(saltMaster)
    //}

    //stage("Install Kubernetes control") {
    //    salt.installOpenstackMcpControl(saltMaster)
    //}

}
