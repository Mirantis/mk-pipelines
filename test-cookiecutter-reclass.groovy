common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
saltModelTesting = new com.mirantis.mk.SaltModelTesting()

slave_node = 'python&&docker'
def reclassVersion = 'v1.5.4'
if (common.validInputParam('RECLASS_VERSION')) {
  reclassVersion = RECLASS_VERSION
}

def generateSaltMaster(modEnv, clusterDomain, clusterName) {
  def nodeFile = "${modEnv}/nodes/cfg01.${clusterDomain}.yml"
  def nodeString = """classes:
- cluster.${clusterName}.infra.config
parameters:
    _param:
        linux_system_codename: xenial
        reclass_data_revision: master
    linux:
        system:
            name: cfg01
            domain: ${clusterDomain}
"""
  sh "mkdir -p ${modEnv}/nodes/"
  println "Create file ${nodeFile}"
  writeFile(file: nodeFile, text: nodeString)
}

def GetBaseName(line, remove_ext) {
 filename = line.toString().split('/').last()
 if (remove_ext && filename.endsWith(remove_ext.toString())) {
   filename = filename.take(filename.lastIndexOf(remove_ext.toString()))
 }
 return filename
}

def generateModel(modelFile, cutterEnv) {
  def templateEnv = "${env.WORKSPACE}"
  def modelEnv = "${env.WORKSPACE}/model"
  def basename = GetBaseName(modelFile, '.yml')
  def generatedModel = "${modelEnv}/${basename}"
  def testEnv = "${env.WORKSPACE}/test"
  def content = readFile(file: "${templateEnv}/contexts/${modelFile}")
  def templateContext = readYaml text: content
  def clusterDomain = templateContext.default_context.cluster_domain
  def clusterName = templateContext.default_context.cluster_name
  def outputDestination = "${generatedModel}/classes/cluster/${clusterName}"
  def targetBranch = "feature/${clusterName}"
  def templateBaseDir = "${env.WORKSPACE}"
  def templateDir = "${templateEnv}/dir"
  def templateOutputDir = templateBaseDir
  sh(script: "rm -rf ${generatedModel} || true")

  common.infoMsg("Generating model from context ${modelFile}")

  def productList = ["infra", "cicd", "opencontrail", "kubernetes", "openstack", "oss", "stacklight", "ceph"]
  for (product in productList) {

    // get templateOutputDir and productDir
    if (product.startsWith("stacklight")) {
      templateOutputDir = "${env.WORKSPACE}/output/stacklight"
      try {
        productDir = "stacklight" + templateContext.default_context['stacklight_version']
      } catch (Throwable e) {
        productDir = "stacklight1"
      }
    } else {
      templateOutputDir = "${env.WORKSPACE}/output/${product}"
      productDir = product
    }

    if (product == "infra" || (templateContext.default_context["${product}_enabled"]
        && templateContext.default_context["${product}_enabled"].toBoolean())) {

      templateDir = "${templateEnv}/cluster_product/${productDir}"
      common.infoMsg("Generating product " + product + " from " + templateDir + " to " + templateOutputDir)

      sh "rm -rf ${templateOutputDir} || true"
      sh "mkdir -p ${templateOutputDir}"
      sh "mkdir -p ${outputDestination}"

      python.buildCookiecutterTemplate(templateDir, content, templateOutputDir, cutterEnv, templateBaseDir)
      sh "mv -v ${templateOutputDir}/${clusterName}/* ${outputDestination}"
    } else {
      common.warningMsg("Product " + product + " is disabled")
    }
  }
  generateSaltMaster(generatedModel, clusterDomain, clusterName)
}


def testModel(modelFile, testEnv, reclassVersion='v1.5.4') {
  // modelFile - `modelfiname` from model/modelfiname/modelfiname.yaml
  // testEnv - path for model (model/modelfilename/)
  //* Grub all models and send it to check in paralell - by one in thread.

  _values_string =  """
  ---
  MODELS_TARGZ: "${env.BUILD_URL}/artifact/reclass.tar.gz"
  DockerCName: "${env.JOB_NAME.toLowerCase()}_${env.BUILD_TAG.toLowerCase()}_${modelFile.toLowerCase()}"
  testReclassEnv: "model/${modelFile}/"
  modelFile: "contexts/${modelFile}.yml"
  DISTRIB_REVISION: "${DISTRIB_REVISION}"
  EXTRA_FORMULAS: "${env.EXTRA_FORMULAS}"
  reclassVersion: "${reclassVersion}"
  """
  build job: "test-mk-cookiecutter-templates-chunk", parameters: [
  [$class: 'StringParameterValue', name: 'EXTRA_VARIABLES_YAML', value: _values_string.stripIndent() ],
  ]
}

def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
  } catch (MissingPropertyException e) {
    gerritRef = null
  }

def testModelStep(basename,testEnv) {
  // We need to wrap what we return in a Groovy closure, or else it's invoked
  // when this method is called, not when we pass it to parallel.
  // To do this, you need to wrap the code below in { }, and either return
  // that explicitly, or use { -> } syntax.
  return {
    node(slave_node) {
      testModel(basename, testEnv)
    }
  }
}

timeout(time: 2, unit: 'HOURS') {
  node(slave_node) {
    def templateEnv = "${env.WORKSPACE}"
    def cutterEnv = "${env.WORKSPACE}/cutter"
    def jinjaEnv = "${env.WORKSPACE}/jinja"

    try {
      // Fixme. Just use 'cleanup workspace' option.
      stage("Cleanup") {
        sh(script:  'find . -mindepth 1 -delete > /dev/null || true')
      }

      stage('Download Cookiecutter template') {
        if (gerritRef) {
          def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID)
          merged = gerritChange.status == "MERGED"
          if (!merged) {
            checkouted = gerrit.gerritPatchsetCheckout([
              credentialsId: CREDENTIALS_ID
              ])
            } else {
              common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to gate them")
            }
            } else {
              git.checkoutGitRepository(templateEnv, COOKIECUTTER_TEMPLATE_URL, COOKIECUTTER_TEMPLATE_BRANCH, CREDENTIALS_ID)
            }
          }

          stage("Setup") {
            python.setupCookiecutterVirtualenv(cutterEnv)
          }

          stage("Check workflow_definition") {
            sh(script: "python ${env.WORKSPACE}/workflow_definition_test.py")
          }

          def contextFileList = []
          dir("${templateEnv}/contexts") {
            for (String x : findFiles(glob: "*.yml")) {
              contextFileList.add(x)
            }
          }

          stage("generate-model") {
            for (contextFile in contextFileList) {
              generateModel(contextFile, cutterEnv)
            }
          }

          dir("${env.WORKSPACE}") {
          // Collect only models. For backward compatability - who know, probably someone use it..
          sh(script: "tar -czf model.tar.gz -C model ../contexts .", returnStatus: true)
          archiveArtifacts artifacts: "model.tar.gz"
          // to be able share reclass for all subenvs
          // Also, makes artifact test more solid - use one reclass for all of sub-models.
          // Archive Structure will be:
          // tar.gz
          // ├── contexts
          // │   └── ceph.yml
          // ├── global_reclass <<< reclass system
          // ├── model
          // │   └── ceph       <<< from `context basename`
          // │       ├── classes
          // │       │   ├── cluster
          // │       │   └── system -> ../../../global_reclass
          // │       └── nodes
          // │           └── cfg01.ceph-cluster-domain.local.yml

          if (SYSTEM_GIT_URL == "") {
            git.checkoutGitRepository("${env.WORKSPACE}/global_reclass/", RECLASS_MODEL_URL, RECLASS_MODEL_BRANCH, CREDENTIALS_ID)
            } else {
              dir("${env.WORKSPACE}/global_reclass/") {
                if (!gerrit.gerritPatchsetCheckout(SYSTEM_GIT_URL, SYSTEM_GIT_REF, "HEAD", CREDENTIALS_ID)) {
                  common.errorMsg("Failed to obtain system reclass with url: ${SYSTEM_GIT_URL} and ${SYSTEM_GIT_REF}")
                  throw new RuntimeException("Failed to obtain system reclass")
                }
              }
            }
            // link all models, to use one global reclass
            for (String context : contextFileList) {
              def basename = GetBaseName(context, '.yml')
              dir("${env.WORKSPACE}/model/${basename}"){
                sh(script: 'mkdir -p classes/; ln -sfv ../../../global_reclass classes/system ')
              }
            }
            // Save all models and all contexts. Warning! `h` flag has been used.
            sh(script: "tar -chzf reclass.tar.gz --exclude='*@tmp' model contexts global_reclass", returnStatus: true)
            archiveArtifacts artifacts: "reclass.tar.gz"
          }

          stage("test-contexts") {
            stepsForParallel = [:]
            common.infoMsg("Found: ${contextFileList.size()} contexts to test.")
            for (String context : contextFileList) {
              def basename = GetBaseName(context, '.yml')
              def testEnv = "${env.WORKSPACE}/model/${basename}"
              stepsForParallel.put("Test:${basename}", testModelStep(basename, testEnv))
            }
            parallel stepsForParallel
            common.infoMsg('All tests done')
          }

          stage('Clean workspace directories') {
            sh(script:  'find . -mindepth 1 -delete > /dev/null || true')
          }

} catch (Throwable e) {
  currentBuild.result = "FAILURE"
  currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
  throw e
  } finally {
    def dummy = "dummy"
      //FAILING common.sendNotification(currentBuild.result,"",["slack"])
    }
  }
}
