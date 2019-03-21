/**
 *
 * Deploy env with l2gw and bgpvpn enabled from cc context
 * using create-mcp-env job and run test on environment and download artifacts
 *
 * Expected parameters:
 *   MCP_ENV_PIPELINES_REFSPEC          Used by rollout-mcp-env and delete-heat-stack-for-mcp-env
 *   MCP_ENV_HEAT_TEMPLATES_REFSPEC     Used by rollout-mcp-env
 *   OPENSTACK_API_PROJECT              OpenStack project name
 *   OPENSTACK_HEAT_AZ                  OpenStack availability zone
 *   OPENSTACK_ENVIRONMENT              OpenStack environment
 *   HEAT_STACK_CONTEXT                 Same as in rollout-mcp-env
 *   STACK_DELETE                       Remove stack after test
 *   COOKIECUTTER_TEMPLATE_CONTEXT_FILE Path to file with base context from heat-templates
 *   COOKIECUTTER_EXTRA_CONTEXT         Overrides base kubernetes_testing context
 *   EXTRA_REPOS                        Yaml based extra repos metadata to be added during bootstrap phase
 *   STACK_NAME                         The name of a stack in openstack (will be generated if empty)
 *   CLUSTER_MODEL_OVERRIDES            List of cluster model yaml files parameters overrides (same as in create-mcp-env)
 *   SALT_MASTER_URL                    Full Salt API address.
 *   CLUSTER_MODEL_OVERRIDES            List of cluster model yaml files parameters overrides (same as in create-mcp-env)
 */

common = new com.mirantis.mk.Common()

def setBuildParameters(inputParams, allowedParams){
    def result = []
    allowedParams.each { param ->
        if (inputParams.containsKey(param.name)) {
            def value = inputParams[param.name]
            def value_class = 'StringParameterValue'
            switch (param.type) {
                case 'boolean':
                    value = value.toBoolean()
                    value_class = 'BooleanParameterValue'
                    break
                case 'text':
                    value_class = 'TextParameterValue'
                    break
            }
            result.add([
                    $class: value_class,
                    name: param.name,
                    value: value,
            ])
        }
    }
    return result
}

node ('python') {
    def stack_name
    if (common.validInputParam('STACK_NAME')) {
        stack_name = STACK_NAME
    } else {
        stack_name = BUILD_TAG
    }

    currentBuild.description = stack_name

    try {
        stage ('Deploy cluster') {
            deploy_build = build (job: "create-mcp-env", parameters: [
                    [$class: 'StringParameterValue', name: 'REFSPEC', value: MCP_ENV_PIPELINES_REFSPEC],
                    [$class: 'StringParameterValue', name: 'HEAT_TEMPLATES_REFSPEC', value: MCP_ENV_HEAT_TEMPLATES_REFSPEC],
                    [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OPENSTACK_API_PROJECT],
                    [$class: 'StringParameterValue', name: 'OS_AZ', value: OPENSTACK_HEAT_AZ],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_ENVIRONMENT', value: OPENSTACK_ENVIRONMENT],
                    [$class: 'StringParameterValue', name: 'STACK_NAME', value: stack_name],
                    [$class: 'StringParameterValue', name: 'COOKIECUTTER_TEMPLATE_CONTEXT_FILE', value: COOKIECUTTER_TEMPLATE_CONTEXT_FILE],
                    [$class: 'TextParameterValue', name: 'HEAT_STACK_CONTEXT', value: HEAT_STACK_CONTEXT],
                    [$class: 'TextParameterValue', name: 'COOKIECUTTER_EXTRA_CONTEXT', value: COOKIECUTTER_EXTRA_CONTEXT],
                    [$class: 'TextParameterValue', name: 'EXTRA_REPOS', value: EXTRA_REPOS],
                    [$class: 'TextParameterValue', name: 'CLUSTER_MODEL_OVERRIDES', value: CLUSTER_MODEL_OVERRIDES],
                ]
            )
        }

        if (Boolean.valueOf(RUN_TESTS)) {
            stage ('Run networking tests') {
                common.infoMsg('TODO')
            }
        }

        // get salt master url
        saltMasterUrl = "http://${deploy_build.description.tokenize(' ')[1]}:6969"

    } finally {
        if (Boolean.valueOf(STACK_DELETE)) {
            stage ('Delete stack') {
                common.infoMsg("Trying to delete stack ${stack_name}")
                build (job: 'delete-heat-stack-for-mcp-env', propagate: true, parameters: [
                        [$class: 'StringParameterValue', name: 'REFSPEC', value: MCP_ENV_PIPELINES_REFSPEC],
                        [$class: 'StringParameterValue', name: 'OS_PROJECT_NAME', value: OPENSTACK_API_PROJECT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_ENVIRONMENT', value: OPENSTACK_ENVIRONMENT],
                        [$class: 'StringParameterValue', name: 'STACK_NAME', value: stack_name],
                    ]
                )
            }
        }
    }
}
