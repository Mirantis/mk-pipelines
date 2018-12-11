/*
 Global CI wrapper for testing next projects:
   - salt-models/reclass-system
   - mk/cookiecutter-templates
 */

import groovy.json.JsonOutput
gerrit = new com.mirantis.mk.Gerrit()

cookiecutterTemplatesRepo='mk/cookiecutter-templates'
reclassSystemRepo='salt-models/reclass-system'
slaveNode = env.getProperty('SLAVE_NODE') ?: 'python&&docker'

voteMatrix = [
  'test-mk-cookiecutter-templates': true,
  'test-drivetrain': true,
  'oscore-test-cookiecutter-models': false,
  'test-salt-model-infra': true,
  'test-salt-model-mcp-virtual-lab': true,
]

baseGerritConfig = [:]
jobResultComments = [:]
commentLock = false

// post Gerrit review comment to patch
def setGerritReviewComment() {
    if (baseGerritConfig) {
        while(commentLock) {
            sleep 5
        }
        commentLock = true
        LinkedHashMap config = baseGerritConfig.clone()
        String jobResultComment = ''
        jobResultComments.each { job, info ->
            String skipped = voteMatrix.get(job, 'true') ? '' : '(non-voting)'
            jobResultComment += "- ${job} ${info.url}console : ${info.status} ${skipped}".trim() + '\n'
        }
        config['message'] = sh(script: "echo '${jobResultComment}'", returnStdout: true).trim()
        gerrit.postGerritComment(config)
        commentLock = false
    }
}

// get job parameters for YAML-based job parametrization
def yamlJobParameters(LinkedHashMap jobParams) {
    return [
        [$class: 'TextParameterValue', name: 'EXTRA_VARIABLES_YAML', value: JsonOutput.toJson(jobParams) ]
    ]
}

// run needed job with params
def runTests(String jobName, ArrayList jobParams) {
    def propagateStatus = voteMatrix.get(jobName, true)
    return {
        def jobBuild = build job: jobName, propagate: false, parameters: jobParams
        jobResultComments[jobName] = [ 'url': jobBuild.absoluteUrl, 'status': jobBuild.result ]
        setGerritReviewComment()
        if (propagateStatus && jobBuild.result == 'FAILURE') {
            throw new Exception("Build ${jobName} is failed!")
        }
    }
}

timeout(time: 12, unit: 'HOURS') {
    node(slaveNode) {
        def common = new com.mirantis.mk.Common()
        def git = new com.mirantis.mk.Git()
        def python = new com.mirantis.mk.Python()

        // Var EXTRA_VARIABLES_YAML contains any additional parameters for tests,
        // like manually specified Gerrit Refs/URLs, additional parameters and so on
        def buildTestParams = [:]
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
        String gateMode = job_env.get('GERRIT_CI_MERGE_TRIGGER', false)

        // Common and manual build parameters
        LinkedHashMap projectsMap = [:]
        String distribRevision = 'nightly'
        //checking if the branch is from release
        if (gerritBranch.startsWith('release')) {
            def distribRevisionRelease = gerritBranch.tokenize('/')[-1]
            if (!common.checkRemoteBinary([apt_mk_version: distribRevisionRelease]).linux_system_repo_url) {
              common.infoMsg("Binary release ${distribRevisionRelease} does not exist on http://mirror.mirantis.com. Falling back to 'proposed'.")
              distribRevision = 'proposed'
            }
            distribRevision = distribRevisionRelease
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
            def defaultURL =  "${gerritScheme}://${gerritName}@${gerritHost}:${gerritPort}"
            projectsMap[gerritProject] = [
                'url': "${defaultURL}/${gerritProject}",
                'ref': gerritRef,
                'branch': gerritBranch,
            ]
            buildType = 'Gerrit Trigger'
            buildTestParams << job_env.findAll { k,v -> k ==~ /GERRIT_.+/ }
            baseGerritConfig = [
                'gerritName': gerritName,
                'gerritHost': gerritHost,
                'gerritChangeNumber': gerritChangeNumber,
                'credentialsId': gerritCredentials,
                'gerritPatchSetNumber': gerritPatchSetNumber,
            ]
            ArrayList descriptionMsgs = [ "Running with next parameters:" ]
            for(String project in projectsMap.keySet()) {
                descriptionMsgs.add("Ref for ${project} => ${projectsMap[project]['ref']}")
                descriptionMsgs.add("Branch for ${project} => ${projectsMap[project]['branch']}")
            }
            descriptionMsgs.add("Distrib revision => ${distribRevision}")
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
            String branchJobName = ''

            if (gerritProject == reclassSystemRepo && gerritBranch == 'master') {
                sh("git diff-tree --no-commit-id --diff-filter=d --name-only -r HEAD  | grep .yml | xargs -I {}  python -c \"import yaml; yaml.load(open('{}', 'r'))\" \\;")
                for (int i = 0; i < testModels.size(); i++) {
                    def cluster = testModels[i]
                    def clusterGitUrl = projectsMap[reclassSystemRepo]['url'].substring(0, projectsMap[reclassSystemRepo]['url'].lastIndexOf("/") + 1) + cluster
                    branchJobName = "test-salt-model-${cluster}"
                    def jobParams = [
                        [$class: 'StringParameterValue', name: 'DEFAULT_GIT_URL', value: clusterGitUrl],
                        [$class: 'StringParameterValue', name: 'DEFAULT_GIT_REF', value: "HEAD"],
                        [$class: 'StringParameterValue', name: 'SYSTEM_GIT_URL', value: projectsMap[reclassSystemRepo]['url']],
                        [$class: 'StringParameterValue', name: 'SYSTEM_GIT_REF', value: projectsMap[reclassSystemRepo]['ref'] ],
                    ]
                    branches[branchJobName] = runTests(branchJobName, jobParams)
                }
            }
            if (gerritProject == reclassSystemRepo || gerritProject == cookiecutterTemplatesRepo) {
                branchJobName = 'test-mk-cookiecutter-templates'
                branches[branchJobName] = runTests(branchJobName, yamlJobParameters(buildTestParams))
            }

            if (!gateMode) {
                if (gerritProject == cookiecutterTemplatesRepo) {
                    branchJobName = 'test-drivetrain'
                    branches[branchJobName] = runTests(branchJobName, yamlJobParameters(buildTestParams))
                    branchJobName = 'oscore-test-cookiecutter-models'
                    branches[branchJobName] = runTests(branchJobName, yamlJobParameters(buildTestParams))
                }
            }

            branches.keySet().each { key ->
                if (branches[key] instanceof Closure) {
                    jobResultComments[key] = [ 'url': job_env.get('BUILD_URL'), 'status': 'WAITING' ]
                }
            }
            setGerritReviewComment()
            parallel branches
        }
    }
}
