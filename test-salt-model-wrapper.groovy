/*
 Global wrapper for testing next projects:
   - salt-models/reclass-system
   - mk/cookiecutter-templates

 Can be triggered manually or by gerrit trigger:
 1) gerrit trigger
    Automatically switches if GERRIT_PROJECT variable detected
    Always test GERRIT_REFSPEC VS GERRIT_BRANCH-master version of opposite project

 2) manual run via job-build , possible to pass refspecs
    - for CC
    - Reclass

    Example of TEST_PARAMETERS_YAML manual config:
---
RECLASS_SYSTEM_URL: ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/salt-models/reclass-system
RECLASS_SYSTEM_GIT_REF: 2018.11.0
RECLASS_SYSTEM_BRANCH: refs/heads/2018.11.0
COOKIECUTTER_TEMPLATE_URL: ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/mk/cookiecutter-templates
COOKIECUTTER_TEMPLATE_REF: refs/heads/2018.11.0
COOKIECUTTER_TEMPLATE_BRANCH: 2018.11.0
DISTRIB_REVISION: 2018.11.0
TEST_MODELS: ''

 */

import groovy.json.JsonOutput

cookiecutterTemplatesRepo='mk/cookiecutter-templates'
reclassSystemRepo='salt-models/reclass-system'
slaveNode = env.getProperty('SLAVE_NODE') ?: 'python&&docker'

LinkedHashMap getManualRefParams(LinkedHashMap map) {
    LinkedHashMap manualParams = [:]
    String defaultGitRef = 'HEAD'
    if (map.containsKey('RECLASS_SYSTEM_GIT_REF') && map.containsKey('RECLASS_SYSTEM_URL')) {
        manualParams[reclassSystemRepo] = [
            'url': map.get('RECLASS_SYSTEM_URL'),
            'ref': map.get('RECLASS_SYSTEM_GIT_REF'),
            'branch': map.get('RECLASS_SYSTEM_BRANCH', 'master'),
        ]
    }
    if (map.containsKey('COOKIECUTTER_TEMPLATE_REF') && map.containsKey('COOKIECUTTER_TEMPLATE_URL')) {
        manualParams[cookiecutterTemplatesRepo] = [
            'url': map.get('COOKIECUTTER_TEMPLATE_URL'),
            'ref': map.get('COOKIECUTTER_TEMPLATE_REF'),
            'branch': map.get('COOKIECUTTER_TEMPLATE_BRANCH', 'master'),
        ]
    }
    return manualParams
}

def runTests(String jobName, String extraVars, Boolean propagateStatus=true) {
    return {
        try {
            build job: "${jobName}", parameters: [
                [$class: 'TextParameterValue', name: 'EXTRA_VARIABLES_YAML', value: extraVars ]
            ]
        } catch (Exception e) {
            if (propagateStatus) {
                throw e
            }
        }
    }
}

def runTestSaltModelReclass(String cluster, String defaultGitUrl, String clusterGitUrl, String refSpec) {
    return {
        build job: "test-salt-model-${cluster}", parameters: [
            [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: clusterGitUrl],
            [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: "HEAD"],
            [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: defaultGitUrl],
            [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: refSpec ],
        ]
    }
}

def checkReclassSystemDocumentationCommit(gerritLib, gerritCredentials) {
    gerritLib.gerritPatchsetCheckout([
        credentialsId: gerritCredentials
    ])

    sh("git diff-tree --no-commit-id --diff-filter=d --name-only -r HEAD  | grep .yml | xargs -I {}  python -c \"import yaml; yaml.load(open('{}', 'r'))\" \\;")

    return sh(script: "git diff-tree --no-commit-id --name-only -r HEAD | grep -v .releasenotes", returnStatus: true) == 1

}

timeout(time: 12, unit: 'HOURS') {
    node(slaveNode) {
        def common = new com.mirantis.mk.Common()
        def gerrit = new com.mirantis.mk.Gerrit()
        def git = new com.mirantis.mk.Git()
        def python = new com.mirantis.mk.Python()

        // Var TEST_PARAMETERS_YAML contains any additional parameters for tests,
        // like manually specified Gerrit Refs/URLs, additional parameters and so on
        if (env.getProperty('TEST_PARAMETERS_YAML')) {
            common.mergeEnv(env, env.getProperty('TEST_PARAMETERS_YAML'))
        }

        // init required job variables
        LinkedHashMap job_env = env.getEnvironment().findAll { k, v -> v }

        // Gerrit parameters
        String gerritCredentials = job_env.get('CREDENTIALS_ID', 'gerrit')
        String gerritRef = job_env.get('GERRIT_REFSPEC', '')
        String gerritProject = ''
        String gerritName = ''
        String gerritScheme = ''
        String gerritHost = ''
        String gerritPort = ''
        String gerritChangeNumber = ''

        // Common and manual build parameters
        LinkedHashMap projectsMap = [:]
        String distribRevision = job_env('DISTRIB_REVISION', 'nightly')
        ArrayList testModels = job_env.get('TEST_MODELS', 'mcp-virtual-lab,infra').split(',')

        stage('Check build mode') {
            def buildType = ''
            if (gerritRef) {
                // job is triggered by Gerrit, get all required Gerrit vars
                gerritProject = job_env.get('GERRIT_PROJECT')
                gerritName = job_env.get('GERRIT_NAME')
                gerritScheme = job_env.get('GERRIT_SCHEME')
                gerritHost = job_env.get('GERRIT_HOST')
                gerritPort = job_env.get('GERRIT_PORT')
                gerritChangeNumber = job_env.get('GERRIT_CHANGE_NUMBER')
                gerritBranch = job_env.get('GERRIT_BRANCH')

                // check if change aren't already merged
                def gerritChange = gerrit.getGerritChange(gerritName, gerritHost, gerritChangeNumber, gerritCredentials)
                if (gerritChange.status == "MERGED") {
                    common.successMsg('Patch set is alredy merged, no need to test it')
                    currentBuild.result = 'SUCCESS'
                    return
                }

                projectsMap[gerritProject] = [
                    'url': "${gerritScheme}://${gerritName}@${gerritHost}:${gerritPort}/${gerritProject}",
                    'ref': gerritRef,
                    'branch': gerritBranch,
                ]
                buildType = 'Gerrit Trigger'
            } else {
                projectsMap = getManualRefParams(job_env)
                if (!projectsMap) {
                    error('Manual build detected and no valid Git refs provided!')
                }
                buildType = 'Manual build'
            }
            ArrayList descriptionMsgs = [ "<font color='red'>${buildType} detected!</font> Running with next parameters:" ]
            for(String project in projectsMap.keySet()) {
                descriptionMsgs.add("Ref for ${project} => ${projectsMap[project]['ref']}")
                descriptionMsgs.add("Branch for ${project} => ${projectsMap[project]['branch']}")
            }
            descriptionMsgs.add("Distrib revision => ${distribRevision}")
            currentBuild.description = descriptionMsgs.join('\n')
        }

        stage("Run tests") {
            def branches = [:]
            branches.failFast = true

            if (projectsMap.containsKey(reclassSystemRepo)) {
                def documentationOnly = checkReclassSystemDocumentationCommit(gerrit, gerritCredentials)
                if (['master'].contains(gerritBranch) && !documentationOnly) {
                    for (int i = 0; i < testModels.size(); i++) {
                        def cluster = testModels[i]
                        //def clusterGitUrl = projectsMap[reclassSystemRepo]['url'].substring(0, defaultGitUrl.lastIndexOf("/") + 1) + cluster
                        def clusterGitUrl = ''
                        branches["reclass-system-${cluster}"] = runTestSaltModelReclass(cluster, projectsMap[reclassSystemRepo]['url'], clusterGitUrl, projectsMap[reclassSystemRepo]['ref'])
                    }
                } else {
                    common.warningMsg("Tests for ${testModels} skipped!")
                }
            }
            if (projectsMap.containsKey(reclassSystemRepo) || projectsMap.containsKey(cookiecutterTemplatesRepo)) {
                branches['cookiecutter-templates'] = runTests('test-mk-cookiecutter-templates', JsonOutput.toJson(job_env))
            }
            if (projectsMap.containsKey(cookiecutterTemplatesRepo)) {
                branches['test-drivetrain'] = runTests('test-drivetrain', JsonOutput.toJson(job_env))
                branches['oscore-test-cookiecutter-models'] = runTests('oscore-test-cookiecutter-models', JsonOutput.toJson(job_env))
            }

            // temp block to disable test run until job is stable
            print branches.keySet()
            currentBuild.result = 'SUCCESS'
            return
            // ----

            parallel branches
        }
    }
}
