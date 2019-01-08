/**
 * Groovy syntax code testing pipeline
 * CREDENTIALS_ID - gerrit credentials id
 * GRADLE_IMAGE - gradle image name
 *
 **/

common = new com.mirantis.mk.Common()

// Guess username for acessing git repo
String git_user = ''
if (env.GERRIT_USER) {
    git_user = "${env.GERRIT_USER}@"
} else if (env.CREDENTIALS_ID) {
    def mkCommon = new com.mirantis.mk.Common()
    def cred = mkCommon.getCredentials(env.CREDENTIALS_ID, 'key')
    git_user = "${cred.username}@"
}

// Use gerrit parameters if set with fallback to job param
String git_repo_url = env.GERRIT_HOST ? "${env.GERRIT_SCHEME}://${git_user}${env.GERRIT_HOST}:${env.GERRIT_PORT}/${env.GERRIT_PROJECT}" : env.GIT_URL
String git_ref = env.GERRIT_REFSPEC ?: env.GIT_REF
String git_credentials_id = env.CREDENTIALS_ID

String docker_registry = env.DOCKER_REGISTRY ?: 'docker-dev-virtual.docker.mirantis.net'
String docker_image_name = env.IMAGE_NAME ?: 'mirantis/openstack-ci/jenkins-job-tests:latest'

String slave_label = env.SLAVE_LABEL ?: 'docker'

String docker_image = (docker_registry ? "${docker_registry}/" : '') + "${docker_image_name}"

String gradle_report_dir = 'build/reports/codenarc'
String gradle_report_path = gradle_report_dir + '/main.*'
String gradle_log_path = gradle_report_dir + '/main.log'

String jjb_dir = 'jenkins-jobs'

// Make codenarc happy
String scm_class = 'GitSCM'

String reporting_config = '''
codenarcMain {
    reports {
        text.enabled = true
        html.enabled = true
    }
}
'''

// Set current build description
if (env.GERRIT_CHANGE_URL) {
    currentBuild.description = """
    <p>
      Triggered by change: <a href="${env.GERRIT_CHANGE_URL}">${env.GERRIT_CHANGE_NUMBER},${env.GERRIT_PATCHSET_NUMBER}</a><br/>
      Project: <b>${env.GERRIT_PROJECT}</b><br/>
      Branch: <b>${env.GERRIT_BRANCH}</b><br/>
      Subject: <b>${env.GERRIT_CHANGE_SUBJECT}</b><br/>
    </p>
    """
} else {
    currentBuild.description = """
    <p>
      Triggered manually<br/>
      Git repository URL: <b>${git_repo_url}</b><br/>
      Git revision: <b>${git_ref}</b><br/>
    </p>
    """
}
timeout(time: 1, unit: 'HOURS') {
    node(slave_label) {
        // Get & prepare source code
        stage('SCM checkout') {
            echo "Checking out git repository from ${git_repo_url} @ ${git_ref}"

            checkout([
                $class           : scm_class,
                branches         : [
                    [name: 'FETCH_HEAD'],
                ],
                userRemoteConfigs: [
                    [url: git_repo_url, refspec: git_ref, credentialsId: git_credentials_id],
                ],
                extensions       : [
                    [$class: 'WipeWorkspace'],
                ],
            ])

            echo 'Checking out mcp-ci/jenkins-jobs for default configs'
            String jjb_repo_url = "${env.DEFAULT_GIT_BASE_URL ?: 'https://gerrit.mcp.mirantis.net'}/mcp-ci/jenkins-jobs"
            checkout([
                $class           : scm_class,
                userRemoteConfigs: [
                    [url: jjb_repo_url, credentialsId: env.CREDENTIALS_ID],
                ],
                extensions       : [
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: jjb_dir]
                ],
            ])
        }

        // Run test
        stage('CodeNarc') {
            // Check existence of configuration files and use ones from jenkins-jobs if not exists
            sh "test -f build.gradle         || cp ${jjb_dir}/build.gradle ."
            sh "test -f codenarcRules.groovy || cp ${jjb_dir}/codenarcRules.groovy ."
            // Remove not needed anymore jenkins-jobs project
            dir(jjb_dir) {
                deleteDir()
            }

            // Force HTML and plain text reports
            sh "sed -ri '/^\\s*reportFormat\\s*=/ d' build.gradle" // Remove existing report config
            sh "echo '${reporting_config}' >> build.gradle"        // Append new one report configuration

            String userID = sh([script: 'id -u', returnStdout: true]).trim()
            String docker_args = [
                '-u root',
                '-t',
                '--privileged',
                "-e 'WORKSPACE=${env.WORKSPACE}'",
                "-w '${env.WORKSPACE}'",
                "-v '${env.WORKSPACE}':'${env.WORKSPACE}'",
                "--name '${env.BUILD_TAG}'",
            ].join(' ')

            def dockerImage = docker.image(docker_image)
            dockerImage.pull()

            sh "mkdir -p ${gradle_report_dir}"
            catchError {
                dockerImage.withRun(docker_args, '/bin/cat') {
                    sh "docker exec -t -u root ${env.BUILD_TAG} usermod -u ${userID} jenkins"
                    sh "docker exec -t -u jenkins ${env.BUILD_TAG} gradle --no-daemon --info --console=plain --offline check 2>&1 | tee ${gradle_log_path}"
                }
            }

            String gradle_log = readFile([file: gradle_log_path])

            // Don't fail immediately to archive artifacts
            catchError {
                // Using pipe without shell option `pipefail` hides errors by the exit status of the latest command
                // Check gradle output explicitly
                if (gradle_log ==~ /(?ms).*Build failed with an exception.*/) {
                    error 'TEST FAILED!'
                }

                // Normally compilation failure doesn't fail the build
                if (gradle_log ==~ /(?ms).*Compilation failed.*/) {
                    error 'COMPILATION FAILED!'
                }

                // Fail if there are internal errors
                if (gradle_log ==~ /(?ms).*Error processing filePath.*/) {
                    error 'ERROR PROCESSING SOME FILE(S)!'
                }
            }
            sh 'cat build/reports/codenarc/main.txt'
            common.infoMsg("CodeNarc HTML report: ${env.BUILD_URL}artifact/build/reports/codenarc/main.html")

            if (currentBuild.resultIsWorseOrEqualTo('UNSTABLE')) {
                setGerritReview customUrl: "- ${env.JOB_NAME} ${env.BUILD_URL}artifact/build/reports/codenarc/main.html"
            }
        }

        // Save results
        stage('Record test results') {
            archiveArtifacts([
                artifacts        : gradle_report_path,
                allowEmptyArchive: true,
            ])
        }
    }
}
