/*
 Global CI wrapper for testing next projects:
   - salt-models/reclass-system
   - mk/cookiecutter-templates

 Wrapper allows to test cross-project patches, based on
 'Depends-On: http://<gerrit_address>/<change_number>' key phrase
 */

import groovy.json.JsonOutput

gerrit = new com.mirantis.mk.Gerrit()

cookiecutterTemplatesRepo = 'mk/cookiecutter-templates'
reclassSystemRepo = 'salt-models/reclass-system'
slaveNode = env.getProperty('SLAVE_NODE') ?: 'virtual'

voteMatrix = [
    'test-mk-cookiecutter-templates' : true,
    'test-drivetrain'                : true,
    'oscore-test-cookiecutter-models': false,
    'test-salt-model-infra'          : true,
    'test-salt-model-mcp-virtual-lab': false,
]

baseGerritConfig = [:]
buildTestParams = [:]
jobResultComments = [:]
commentLock = false

// post Gerrit review comment to patch
def setGerritReviewComment() {
    if (baseGerritConfig) {
        while (commentLock) {
            sleep 5
        }
        commentLock = true
        LinkedHashMap config = baseGerritConfig.clone()
        String jobResultComment = ''
        jobResultComments.each { threadName, info ->
            String skipped = voteMatrix.get(info.job, 'true') ? '' : '(non-voting)'
            jobResultComment += "- ${threadName} ${info.url}console : ${info.status} ${skipped}".trim() + '\n'
        }
        config['message'] = sh(script: "echo '${jobResultComment}'", returnStdout: true).trim()
        gerrit.postGerritComment(config)
        commentLock = false
    }
}

// get job parameters for YAML-based job parametrization
def yamlJobParameters(LinkedHashMap jobParams) {
    return [
        [$class: 'TextParameterValue', name: 'EXTRA_VARIABLES_YAML', value: JsonOutput.toJson(jobParams)]
    ]
}

// run needed job with params
def runTests(String jobName, ArrayList jobParams, String threadName = '') {
    threadName = threadName ? threadName : jobName
    def propagateStatus = voteMatrix.get(jobName, true)
    return {
        def jobBuild = build job: jobName, propagate: false, parameters: jobParams
        jobResultComments[threadName] = ['url': jobBuild.absoluteUrl, 'status': jobBuild.result, 'job': jobName]
        setGerritReviewComment()
        if (propagateStatus && jobBuild.result == 'FAILURE') {
            throw new Exception("Build ${threadName} is failed!")
        }
    }
}

// set params based on depending patches
def setupDependingVars(LinkedHashMap dependingProjects) {
    if (dependingProjects) {
        if (dependingProjects.containsKey(reclassSystemRepo)) {
            buildTestParams['RECLASS_SYSTEM_GIT_REF'] = dependingProjects[reclassSystemRepo].ref
            buildTestParams['RECLASS_SYSTEM_BRANCH'] = dependingProjects[reclassSystemRepo].branch
        }
        if (dependingProjects.containsKey(cookiecutterTemplatesRepo)) {
            buildTestParams['COOKIECUTTER_TEMPLATE_REF'] = dependingProjects[cookiecutterTemplatesRepo].ref
            buildTestParams['COOKIECUTTER_TEMPLATE_BRANCH'] = dependingProjects[cookiecutterTemplatesRepo].branch
        }
    }
}

timeout(time: 12, unit: 'HOURS') {
    node(slaveNode) {
        def common = new com.mirantis.mk.Common()

        // Var EXTRA_VARIABLES_YAML contains any additional parameters for tests,
        // like manually specified Gerrit Refs/URLs, additional parameters and so on
        def buildTestParamsYaml = env.getProperty('EXTRA_VARIABLES_YAML')
        if (buildTestParamsYaml) {
            common.mergeEnv(env, buildTestParamsYaml)
            buildTestParams = readYaml text: buildTestParamsYaml
        }

        // init required job variables
        LinkedHashMap job_env = env.getEnvironment().findAll { k, v -> v }

        // Gerrit parameters
        String gerritCredentials = job_env.get('CREDENTIALS_ID', 'gerrit')
        String gerritRef = job_env.get('GERRIT_REFSPEC')
        String gerritProject = job_env.get('GERRIT_PROJECT')
        String gerritName = job_env.get('GERRIT_NAME')
        String gerritScheme = job_env.get('GERRIT_SCHEME')
        String gerritHost = job_env.get('GERRIT_HOST')
        String gerritPort = job_env.get('GERRIT_PORT')
        String gerritChangeNumber = job_env.get('GERRIT_CHANGE_NUMBER')
        String gerritPatchSetNumber = job_env.get('GERRIT_PATCHSET_NUMBER')
        String gerritBranch = job_env.get('GERRIT_BRANCH')
        Boolean gateMode = job_env.get('GERRIT_CI_MERGE_TRIGGER', false).toBoolean()

        // Common and manual build parameters
        LinkedHashMap projectsMap = [:]
        String distribRevision = 'nightly'
        //checking if the branch is from release
        if (gerritBranch.startsWith('release')) {
            distribRevision = gerritBranch.tokenize('/')[-1]
            // Check if we are going to test bleeding-edge release, which doesn't have binary release yet
            // After 2018q4 releases, need to also check 'static' repo, for example ubuntu.
            binTest = common.checkRemoteBinary(['mcp_version': distribRevision])
            if (!binTest.linux_system_repo_url || !binTest.linux_system_repo_ubuntu_url) {
                common.errorMsg("Binary release: ${distribRevision} not exist or not full. Fallback to 'proposed'! ")
                distribRevision = 'proposed'
            }
        }
        ArrayList testModels = job_env.get('TEST_MODELS', 'mcp-virtual-lab,infra').split(',')

        stage('Gerrit prepare') {
            // check if change aren't already merged
            def gerritChange = gerrit.getGerritChange(gerritName, gerritHost, gerritChangeNumber, gerritCredentials)
            if (gerritChange.status == "MERGED") {
                common.successMsg('Patch set is alredy merged, no need to test it')
                currentBuild.result = 'SUCCESS'
                return
            }
            buildTestParams << job_env.findAll { k, v -> k ==~ /GERRIT_.+/ }
            baseGerritConfig = [
                'gerritName'          : gerritName,
                'gerritHost'          : gerritHost,
                'gerritPort'          : gerritPort,
                'gerritChangeNumber'  : gerritChangeNumber,
                'credentialsId'       : gerritCredentials,
                'gerritPatchSetNumber': gerritPatchSetNumber,
            ]
            LinkedHashMap gerritDependingProjects = gerrit.getDependentPatches(baseGerritConfig)
            setupDependingVars(gerritDependingProjects)
            ArrayList descriptionMsgs = [
                "Running with next parameters:",
                "Ref for ${gerritProject} => ${gerritRef}",
                "Branch for ${gerritProject} => ${gerritBranch}"
            ]
            descriptionMsgs.add("Distrib revision => ${distribRevision}")
            for (String project in gerritDependingProjects.keySet()) {
                descriptionMsgs.add("---")
                descriptionMsgs.add("Depending patch to ${project} found:")
                descriptionMsgs.add("Ref for ${project} => ${gerritDependingProjects[project]['ref']}")
                descriptionMsgs.add("Branch for ${project} => ${gerritDependingProjects[project]['branch']}")
            }
            currentBuild.description = descriptionMsgs.join('<br/>')
            gerrit.gerritPatchsetCheckout([
                credentialsId: gerritCredentials
            ])
        }

        stage("Run tests") {
            def documentationOnly = sh(script: "git diff-tree --no-commit-id --name-only -r HEAD | grep -v .releasenotes", returnStatus: true) == 1
            if (documentationOnly) {
                common.infoMsg("Tests skipped, documenation only changed!")
                currentBuild.result = 'SUCCESS'
                return
            }

            def branches = [:]
            branches.failFast = false
            String branchJobName = ''

            if (gerritProject == reclassSystemRepo && gerritBranch == 'master') {
                sh("git diff-tree --no-commit-id --diff-filter=d --name-only -r HEAD  | grep .yml | xargs -I {}  python -c \"import yaml; yaml.load(open('{}', 'r'))\" \\;")
                def defaultSystemURL = "${gerritScheme}://${gerritName}@${gerritHost}:${gerritPort}/${gerritProject}"
                for (int i = 0; i < testModels.size(); i++) {
                    def cluster = testModels[i]
                    def clusterGitUrl = defaultSystemURL.substring(0, defaultSystemURL.lastIndexOf("/") + 1) + cluster
                    branchJobName = "test-salt-model-${cluster}"
                    def jobParams = [
                        [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: clusterGitUrl],
                        [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: "HEAD"],
                        [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: defaultSystemURL],
                        [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: gerritRef],
                    ]
                    branches[branchJobName] = runTests(branchJobName, jobParams)
                }
            }
            if (gerritProject == reclassSystemRepo || gerritProject == cookiecutterTemplatesRepo) {
                branchJobName = 'test-mk-cookiecutter-templates'
                branches[branchJobName] = runTests(branchJobName, yamlJobParameters(buildTestParams))
            }

            if (!gateMode) {
                // testing backward compatibility
                if (gerritBranch == 'master' && gerritProject == reclassSystemRepo) {
                    def backwardCompatibilityRefsToTest = ['proposed', 'release/2018.11.0', 'release/2019.2.0']
                    for (String oldRef in backwardCompatibilityRefsToTest) {
                        LinkedHashMap buildTestParamsOld = buildTestParams.clone()
                        buildTestParamsOld['COOKIECUTTER_TEMPLATE_REF'] = ''
                        buildTestParamsOld['COOKIECUTTER_TEMPLATE_BRANCH'] = oldRef
                        String threadName = "${branchJobName}-${oldRef}"
                        branches[threadName] = runTests(branchJobName, yamlJobParameters(buildTestParamsOld), threadName)
                    }
                }
                if (gerritProject == cookiecutterTemplatesRepo) {
                    branchJobName = 'test-drivetrain'
                    branches[branchJobName] = runTests(branchJobName, yamlJobParameters(buildTestParams))
                    branchJobName = 'oscore-test-cookiecutter-models'
                    branches[branchJobName] = runTests(branchJobName, yamlJobParameters(buildTestParams))
                }
            }

            branches.keySet().each { key ->
                if (branches[key] instanceof Closure) {
                    jobResultComments[key] = ['url': job_env.get('BUILD_URL'), 'status': 'WAITING']
                }
            }
            setGerritReviewComment()
            parallel branches
        }
    }
}
