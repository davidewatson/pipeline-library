// /src/io/cnct/pipeline/chartRepoBuilder.groovy
package io.cnct.pipeline;

def executePipeline(pipelineDef) {
  // script globals initialized in initializeHandler
  isChartChange = false
  isMasterBuild = false
  isPRBuild = false
  isSelfTest = false
  pipeline = pipelineDef
  pipelineEnvVariables = []
  pullSecrets = []
  defaults = parseYaml(libraryResource("io/cnct/pipeline/defaults.yaml"))
  slackError = ""

  properties(
    [
      disableConcurrentBuilds()
    ]
  )

  def err = null
  def notifyMessage = ""

  try {
    initializeHandler();

    if (isPRBuild || isSelfTest) {
      runPR()
    }

    if (isSelfTest) {
      initializeHandler();
    }

    if (isMasterBuild || isSelfTest) {
      runMerge()
    }

    notifyMessage = 'Build succeeded for ' + "${env.JOB_NAME} number ${env.BUILD_NUMBER} (${env.RUN_DISPLAY_URL})"
  } catch (e) {
    currentBuild.result = 'FAILURE'
    notifyMessage = 'Build failed for ' + 
      "${env.JOB_NAME} number ${env.BUILD_NUMBER} (${env.RUN_DISPLAY_URL}) : ${e.getMessage()}"
    err = e
  } finally {
    
    if (err) {
      slackFail(pipeline, notifyMessage)
      
      errorCleanup()

      def sw = new StringWriter()
      def pw = new PrintWriter(sw)
      err.printStackTrace(pw)
      echo sw.toString()
      
      throw err
    } else {
      slackOk(pipeline, notifyMessage)
    } 
  }
}

// try to cleanup any hanging helm release or namespaces resulting from premature termination
// through either job ABORT or error
def errorCleanup() {
  if (isPRBuild || isSelfTest) {
    withTools(
      defaults: defaults,
      envVars: pipelineEnvVariables,
      containers: getScriptImages(),
      imagePullSecrets: pullSecrets,
      volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {
      inside(label: buildId('tools')) {
        container('helm') {
          stage('Cleaning up') {
            def helmCleanSteps = [:] 

            for (chart in pipeline.configs) {
              if (chart.chart) {
                def commandString = "helm delete ${chart.release}-${kubeName(env.JOB_NAME)} --purge --tiller-namespace ${pipeline.helm.namespace} || true"
                helmCleanSteps["${chart.chart}-deploy-test"] = { sh(commandString) }
              }
            }

            parallel helmCleanSteps
            sh("kubectl delete namespace ${kubeName(env.JOB_NAME)} || true")
            sh("helm list --namespace ${kubeName(env.JOB_NAME)} --short --failed --tiller-namespace ${pipeline.helm.namespace} | while read line; do helm delete \$line --purge --tiller-namespace ${pipeline.helm.namespace}; done")
            sh("helm list --namespace ${defaults.stageNamespace} --short --failed --tiller-namespace ${pipeline.helm.namespace} | while read line; do helm delete \$line --purge --tiller-namespace ${pipeline.helm.namespace}; done")
          }
        }
      }
    }
  }
}

def initializeHandler() {
  for (pull in pipeline.pullSecrets ) {
    pullSecrets += pull.name
  }

  // collect the env values to be injected
  pipelineEnvVariables += containerEnvVar(key: 'DOCKER_HOST', value: 'localhost:2375')
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_ADDR', value: pipeline.vault.server)
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_CACERT', value: "/etc/vault/tls/${pipeline.vault.tls.ca}")
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_CLIENT_CERT', value: "/etc/vault/tls/${pipeline.vault.tls.cert}")
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_CLIENT_KEY', value: "/etc/vault/tls/${pipeline.vault.tls.key}")

  // create pull secrets 
  withTools(
    envVars: pipelineEnvVariables, 
    defaults: defaults,
    volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {
    inside(label: buildId('tools')) {
      container('helm') {
        stage('Create image pull secrets') {
          for (pull in pipeline.pullSecrets ) {
            withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
              def vaultToken = env.VAULT_TOKEN
              def secretVal = getVaultKV(
                defaults,
                vaultToken,
                pull.password.tokenize('/').init().join('/'), 
                pull.password.tokenize('/').last())

              def deleteSecrets = """
                kubectl delete secret ${pull.name} --namespace=${defaults.jenkinsNamespace} || true"""
              def createSecrets = """
                set +x
                kubectl create secret docker-registry ${pull.name} \
                  --docker-server=${pull.server} \
                  --docker-username=${pull.username} \
                  --docker-password='${secretVal}' \
                  --docker-email='${pull.email}' --namespace=${defaults.jenkinsNamespace}
                set -x"""

              sh(deleteSecrets)
              sh(createSecrets)
            }
          }
        }
      }

      container('vault') {
        stage('Set global environment variables') {
          for (envValue in pipeline.envValues ) {
            if (envValue.secret) {
              withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
                def vaultToken = env.VAULT_TOKEN
                def secretVal = getVaultKV(
                  defaults,
                  vaultToken,
                  envValue.secret.tokenize('/').init().join('/'), 
                  envValue.secret.tokenize('/').last())
                pipelineEnvVariables += envVar(
                  key: envValue.envVar, 
                  value: secretVal)
              }
            } else {
              pipelineEnvVariables += envVar(
                key: envValue.envVar, 
                value: envValue.value)
            }
          }
        }
      }
    }
  } 

  // init all the conditionals we care about
  // This is a PR build if CHANGE_ID is set by git SCM
  isPRBuild = (env.CHANGE_ID) ? true : false
  // This is a master build if this is not a PR build
  isMasterBuild = !isPRBuild
  // TODO: this would be initialized from a job parameter.
  isSelfTest = false
}

def runPR() {
  def scmVars
  withTools(
    defaults: defaults,
    envVars: pipelineEnvVariables,
    containers: getScriptImages(),
    imagePullSecrets: pullSecrets,
    volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {       
    
    inside(label: buildId('tools')) {
      stage('Checkout') {
        scmVars = checkout scm
      }
    

      // run before scripts
      executeUserScript('Executing global \'before\' scripts', pipeline.beforeScript)

      rootFsTestHandler(scmVars)
      chartLintHandler(scmVars)

      try {
        deployToTestHandler(scmVars)
        helmTestHandler(scmVars)
        testTestHandler(scmVars)
      } finally {
        destroyHandler(scmVars)
      }
      
      rootFsStageHandler(scmVars)
      deployToStageHandler(scmVars)
      stageTestHandler(scmVars)

      // run after scripts
      executeUserScript('Executing global \'after\' scripts', pipeline.afterScript)
    }
  }
}

def runMerge() {
  def scmVars
  withTools(
    defaults: defaults,
    containers: getScriptImages(),
    envVars: pipelineEnvVariables,
    imagePullSecrets: pullSecrets,
    volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {
    inside(label: buildId('tools')) {
      stage('Checkout') {
        scmVars = checkout scm
      }

      // run before scripts
      executeUserScript('Executing global \'before\' scripts', pipeline.beforeScript)

      rootFsProdHandler(scmVars)
      chartProdHandler(scmVars)
      deployToProdHandler(scmVars)

      // run after scripts
      executeUserScript('Executing global \'after\' scripts', pipeline.afterScript)
    }
  }
}

// Build changed rootfs folders 
// Tag with commit sha
// Tag with a test tag
// then push to repo
def rootFsTestHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelBuildSteps = [:]
  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  for (container in pipeline.rootfs) {
    // build steps
    def buildCommandString = "docker build -t \
      ${defaults.docker.registry}/${container.image}:${useTag} --pull " 
    if (container.buildArgs) {
      buildCommandString += mapToParams('--build-arg', container.buildArgs)
    }
    buildCommandString += " rootfs/${container.context}"
    parallelBuildSteps["${container.image.replaceAll('/','_')}-build"] = { sh(buildCommandString) }

    // tag steps
    def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag} \
     ${defaults.docker.registry}/${container.image}:${defaults.docker.testTag}"
    parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }

    // push steps
    def pushShaCommandString = "docker push ${defaults.docker.registry}/${container.image}:${useTag}"
    def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.testTag}"
    
    parallelPushSteps["${container.image.replaceAll('/','_')}-push-sha"] = { sh(pushShaCommandString) }
    parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }

    
    if (container.chart) {
      chartsWithContainers += container
    }
  }
  container('docker') {
    stage("Building docker files, tagging with ${gitCommit} and ${defaults.docker.testTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('docker login --username $DOCKER_USER --password $DOCKER_PASSWORD ' + defaults.docker.registry)
      }

      parallel parallelBuildSteps
      parallel parallelTagSteps
      parallel parallelPushSteps

      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash
      for (chart in chartsWithContainers) {
        def valuesYaml = parseYaml(readFile("${pwd()}/charts/${chart.chart}/values.yaml"))

        mapValueByPath(chart.value, valuesYaml, "${defaults.docker.registry}/${chart.image}:${useTag}")
        toYamlFile(valuesYaml, "${pwd()}/charts/${chart.chart}/values.yaml")

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "charts/${chart.chart}/values.yaml"
        )
      }
    }
  }
}

// Tag changed repos with a stage tag
// then push to repo
def rootFsStageHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  for (container in pipeline.rootfs) {
    // tag steps
    def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag} \
     ${defaults.docker.registry}/${container.image}:${defaults.docker.stageTag}"
    parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }

    // push steps
    def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.stageTag}"
    parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }

    if (container.chart) {
      chartsWithContainers += container
    }
  }

  container('docker') {
    stage("Tagging with ${defaults.docker.stageTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('docker login --username $DOCKER_USER --password $DOCKER_PASSWORD ' + defaults.docker.registry)
      }

      parallel parallelTagSteps
      parallel parallelPushSteps

      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash
      for (chart in chartsWithContainers) {
        def valuesYaml = parseYaml(readFile("${pwd()}/charts/${chart.chart}/values.yaml"))

        mapValueByPath(chart.value, valuesYaml, "${defaults.docker.registry}/${chart.image}:${useTag}")
        toYamlFile(valuesYaml, "${pwd()}/charts/${chart.chart}/values.yaml")

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "charts/${chart.chart}/values.yaml"
        )
      }
    }
  }
}

// tag changed rootfs folders with prod tag
// then push to repo
def rootFsProdHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelBuildSteps = [:]
  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  // Collect all the docker build steps as 'docker build' command string
  // for later execution in parallel
  // Also memoize the rootfs objects, if they are connected to in-repo charts
  for (container in pipeline.rootfs) {
    // build steps
    def buildCommandString = "docker build -t \
      ${defaults.docker.registry}/${container.image}:${useTag} --pull " 
    if (container.buildArgs) {
      buildCommandString += mapToParams('--build-arg', container.buildArgs)
    }
    buildCommandString += " rootfs/${container.context}"
    parallelBuildSteps["${container.image.replaceAll('/','_')}-build"] = { sh(buildCommandString) }

    def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag} \
     ${defaults.docker.registry}/${container.image}:${defaults.docker.prodTag}"
    parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }


    def pushShaCommandString = "docker push ${defaults.docker.registry}/${container.image}:${useTag}"
    def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.prodTag}"
    parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }
    parallelPushSteps["${container.image.replaceAll('/','_')}-push-sha"] = { sh(pushShaCommandString) }

    if (container.chart) {
      chartsWithContainers += container
    }
  }

  container('docker') {
    stage("Tagging with ${defaults.docker.prodTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('docker login --username $DOCKER_USER --password $DOCKER_PASSWORD ' + defaults.docker.registry)
      }

      parallel parallelBuildSteps
      parallel parallelTagSteps
      parallel parallelPushSteps

      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash
      for (chart in chartsWithContainers) {
        def valuesYaml = parseYaml(readFile("${pwd()}/charts/${chart.chart}/values.yaml"))

        mapValueByPath(chart.value, valuesYaml, "${defaults.docker.registry}/${chart.image}:${useTag}")
        toYamlFile(valuesYaml, "${pwd()}/charts/${chart.chart}/values.yaml")

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "charts/${chart.chart}/values.yaml"
        )
      }
    }
  }
}

// Use helm lint to go over everything that changed 
// under /charts folder
def chartLintHandler(scmVars) { 
  def parallelLintSteps = [:]   
  def versionFileContents = readFile(defaults.versionfile) 

  // read in all appropriate versionfiles and replace Chart.yaml versions 
  // this will verify that version files had helm-valid version numbers during linting step
  for (chart in pipeline.configs) { 
    if (chart.chart) {
      // load chart yaml
      def chartYaml = parseYaml(readFile("${pwd()}/charts/${chart.chart}/Chart.yaml"))

      // build new chart version
      def verComponents = []
      verComponents.addAll(chartYaml.version.toString().split('\\+'))

      if (verComponents.size() > 1) {
        verComponents[1] = scmVars.GIT_COMMIT
      } else {
        verComponents << scmVars.GIT_COMMIT
      }

      verComponents[0] = versionFileContents + "-test.${env.BUILD_NUMBER}"
      
      chartYaml.version = verComponents.join('+')

      toYamlFile(chartYaml, "${pwd()}/charts/${chart.chart}/Chart.yaml")

      // stash the Chart.yaml
      stash(
        name: "${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'),
        includes: "charts/${chart.chart}/Chart.yaml"
      )

      // grab current config object that is applicable to test section from all configs
      def commandString = "helm lint charts/${chart.chart}"
      parallelLintSteps["${chart.chart}-lint"] = { sh(commandString) }
    } 
  }

  container('helm') {
    stage('Linting charts') {
      for (chart in pipeline.configs) {
        // unstash chart yaml changes
        unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

        // unstash values changes if applicable
        unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))
      }

      parallel parallelLintSteps
    }
  }
}

// upload charts to helm registry
def chartProdHandler(scmVars) {
  def versionFileContents = readFile(defaults.versionfile)
  def parallelChartSteps = [:] 
  
  container('helm') {
    stage('Preparing chart for prod') {
      for (chart in pipeline.configs) {
        if (chart.chart) {

          // load chart yaml
          def chartYaml = parseYaml(readFile("${pwd()}/charts/${chart.chart}/Chart.yaml"))

          // build new chart version
          def verComponents = []
          verComponents.addAll(chartYaml.version.toString().split('\\+'))

          if (verComponents.size() > 1) {
            verComponents[1] = scmVars.GIT_COMMIT
          } else {
            verComponents << scmVars.GIT_COMMIT
          }

          verComponents[0] = versionFileContents + "-prod.${env.BUILD_NUMBER}"

          chartYaml.version = verComponents.join('+')
          toYamlFile(chartYaml, "${pwd()}/charts/${chart.chart}/Chart.yaml")

          // stash the Chart.yaml
          stash(
            name: "${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'),
            includes: "charts/${chart.chart}/Chart.yaml"
          )

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // package chart, send it to registry
          parallelChartSteps["${chart.chart}-upload"] = {
            withCredentials(
              [usernamePassword(
                credentialsId: defaults.helm.credentials, 
                usernameVariable: 'REGISTRY_USER',
                passwordVariable: 'REGISTRY_PASSWORD')]) {
                def registryUser = env.REGISTRY_USER
                def registryPass = env.REGISTRY_PASSWORD
                sh("""
                  helm init --client-only
                  helm repo add pipeline https://${defaults.helm.registry}
                  helm dependency update --debug charts/${chart.chart}
                  helm package --debug charts/${chart.chart}
                  curl -u ${registryUser}:${registryPass} --data-binary @${chart.chart}-${chartYaml.version}.tgz https://${defaults.helm.registry}/api/charts""")
            }
          }
        }
      }

      parallel parallelChartSteps
    }

    stage('Archive artifacts') {
      archiveArtifacts(artifacts: '*.tgz', allowEmptyArchive: true)
    }
  }
}

// deploy chart from source into testing namespace
def deployToTestHandler(scmVars) {
  executeUserScript('Executing test \'before\' script', pipeline.test.beforeScript)
  
  container('helm') {
    stage('Deploying to test namespace') {
      def deploySteps = [:]
      for (chart in pipeline.configs) {
        if (chart.chart) {
          // unstash chart yaml if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // deploy chart to the correct namespace
          def commandString = """
          set +x
          helm init --client-only
          helm repo add pipeline https://${defaults.helm.registry}
          helm dependency update --debug charts/${chart.chart}
          helm package --debug charts/${chart.chart}
          helm install charts/${chart.chart} --tiller-namespace ${pipeline.helm.namespace} --namespace ${kubeName(env.JOB_NAME)} --name ${chart.release}-${kubeName(env.JOB_NAME)}""" 

          
          def setParams = envMapToSetParams(chart.test.values)
          commandString += setParams

          deploySteps["${chart.chart}-deploy-test"] = { sh(commandString) }
        }
      }

      parallel deploySteps
    }

    stage('Archive artifacts') {
      archiveArtifacts(artifacts: '*.tgz', allowEmptyArchive: true)
    }
  }
}

// deploy chart from source into staging namespace
def deployToStageHandler(scmVars) { 
  executeUserScript('Executing stage \'before\' script', pipeline.stage.beforeScript)
  
  container('helm') {
    stage('Deploying to stage namespace') {
      def deploySteps = [:]
      for (chart in pipeline.configs) {
        if (chart.chart) {
          // unstash chart yaml if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // deploy chart to the correct namespace
          def commandString = """
          set +x
          helm init --client-only
          helm dependency update --debug charts/${chart.chart}
          helm upgrade --install --tiller-namespace ${pipeline.helm.namespace} --namespace ${defaults.stageNamespace} ${chart.release}-${defaults.stageNamespace} charts/${chart.chart}""" 
          
          def setParams = envMapToSetParams(chart.stage.values)
          commandString += setParams

          deploySteps["${chart.chart}-deploy-stage"] = { sh(commandString) }
        }
      }

      parallel deploySteps              
    }
  }
}

// deploy chart from repository into prod namespace, 
// conditional on doDeploy
def deployToProdHandler(scmVars) {
  executeUserScript('Executing prod \'before\' script', pipeline.prod.beforeScript) 
  def versionfileChanged = isPathChange(defaults.versionfile, "${env.CHANGE_ID}")

  container('helm') {
    def deploySteps = [:]
    stage('Deploying to prod namespace') {
      for (chart in pipeline.configs) {
        if (chart.chart) {

          // unstash chart yaml changes if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          chartYaml = parseYaml(readFile("${pwd()}/charts/${chart.chart}/Chart.yaml"))

          // determine if we need to deploy
          def doDeploy = false
          if (pipeline.prod.doDeploy == 'auto') {
            doDeploy = true
          } else if (pipeline.prod.doDeploy == 'versionfile') {
            if (versionfileChanged == 0) {
              doDeploy = true
            }
          }

          // deploy chart to the correct namespace
          if (doDeploy) {
            def commandString = """
            set +x
            helm init --client-only
            helm repo add pipeline https://${defaults.helm.registry}
            helm dependency update --debug charts/${chart.chart}
            helm upgrade --install --tiller-namespace ${pipeline.helm.namespace} --repo https://${defaults.helm.registry} --version ${chartYaml.version} --namespace ${defaults.prodNamespace} ${chart.release} ${chart.chart}""" 
            
            def setParams = envMapToSetParams(chart.prod.values)
            commandString += setParams

            deploySteps["${chart.chart}-deploy-prod"] = { sh(commandString) }
          }
        }
      }

      parallel deploySteps              
    }
  }

  executeUserScript('Executing prod \'after\' script', pipeline.prod.afterScript)
}

// run helm tests
def helmTestHandler(scmVars) {
  container('helm') {
    stage('Running helm tests') {
      for (chart in pipeline.configs) {
        if (chart.chart) {
          def commandString = """
          helm test --cleanup --tiller-namespace ${pipeline.helm.namespace} --timeout ${chart.timeout} ${chart.release}-${kubeName(env.JOB_NAME)}
          """ 

          retry(chart.retries) {
            sh(commandString)
          }
        }
      }
    }
  }
}

// run test tests
def testTestHandler(scmVars) {

  for (config in pipeline.configs) {
    if (config.test.tests) {
      for (test in config.test.tests) {
        executeUserScript('Executing staging test scripts', test)
      }
    }
  }

  executeUserScript('Executing stage \'after\' script', pipeline.test.afterScript) 
}

// run staging tests
def stageTestHandler(scmVars) {

  for (config in pipeline.configs) {
    if (config.stage.tests) {
      for (test in config.stage.tests) {
        executeUserScript('Executing staging test scripts', test)
      }
    }
  }

  executeUserScript('Executing stage \'after\' script', pipeline.stage.afterScript) 
}

// destroy the test namespace
def destroyHandler(scmVars) {
  def destroySteps = [:]

  container('helm') {
    stage('Cleaning up test') {
      for (chart in pipeline.configs) {
        if (chart.chart) {
          def commandString = """
            helm delete ${chart.release}-${kubeName(env.JOB_NAME)} --purge --tiller-namespace ${pipeline.helm.namespace}
            kubectl delete namespace ${kubeName(env.JOB_NAME)}"""

          destroySteps["${chart.release}-${kubeName(env.JOB_NAME)}"] = { sh(commandString) }
        }
      }

      parallel destroySteps
    }
  }

  executeUserScript('Executing test \'after\' script', pipeline.test.afterScript)
}

def envMapToSetParams(envMap) {
  def setParamString = ""
  for (obj in envMap) {
    if (obj.key) {
      if (obj.secret) {
        setParamString += " --set ${obj.key}="
        withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
          def secretVal = getVaultKV(
            defaults,
            env.VAULT_TOKEN,
            obj.secret.tokenize('/').init().join('/'), 
            obj.secret.tokenize('/').last())

          setParamString += """'${secretVal}'"""
        }
      } else if (obj.value) {
        setParamString += " --set ${obj.key}="
        setParamString += """'${obj.value}'"""
      } 
    }
  }

  return setParamString
}

def getScriptImages() {
  // collect script containers
  def scriptContainers = []
  
  def check = {collection, item -> 
    for (existing in collection) {
      if (existing.name == item.name) {
        return
      }
    }

    collection.add(item)
  }

  if (isPRBuild || isSelfTest) {
    if (pipeline.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.beforeScript.image),
        image: pipeline.beforeScript.image,
        shell: pipeline.beforeScript.shell])
    }
    if (pipeline.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.afterScript.image),
        image: pipeline.afterScript.image,
        shell: pipeline.afterScript.shell])
    }
    if (pipeline.test.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.test.afterScript.image),
        image: pipeline.test.afterScript.image,
        shell: pipeline.test.afterScript.shell])
    }
    if (pipeline.test.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.test.beforeScript.image),
        image: pipeline.test.beforeScript.image,
        shell: pipeline.test.beforeScript.shell])
    }
    if (pipeline.stage.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.stage.afterScript.image),
        image: pipeline.stage.afterScript.image,
        shell: pipeline.stage.afterScript.shell])
    }
    if (pipeline.stage.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.stage.beforeScript.image),
        image: pipeline.stage.beforeScript.image,
        shell: pipeline.stage.beforeScript.shell])
    }

    for (config in pipeline.configs) {
      for (test in config.stage.tests) {
        check(scriptContainers, [name: containerName(test.image),
          image: test.image,
          shell: test.shell])
      }
    }
  }

  if (isMasterBuild || isSelfTest) {
    if (pipeline.prod.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.prod.afterScript.image),
        image: pipeline.prod.afterScript.image,
        shell: pipeline.prod.afterScript.shell])
    }
    if (pipeline.prod.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.prod.beforeScript.image),
        image: pipeline.prod.beforeScript.image,
        shell: pipeline.prod.beforeScript.shell])
    }
  }

  return scriptContainers
}

// run any kind of user script defined with :
// ---
// image: registry.com/some-image:tag
// shell: /bin/bash
// script: path/to/some-script.sh
// ---
// yaml definition 
def executeUserScript(stageText, scriptObj) {
  if (scriptObj) {
    stage(stageText) {
      container(containerName(scriptObj.image)) {

        withEnv(
          [
            "PIPELINE_PROD_NAMESPACE=${defaults.prodNamespace}",
            "PIPELINE_STAGE_NAMESPACE=${defaults.stageNamespace}",
            "PIPELINE_TEST_NAMESPACE=${kubeName(env.JOB_NAME)}",
            "PIPELINE_BUILD_ID=${env.BUILD_ID}",
            "PIPELINE_JOB_NAME=${env.JOB_NAME}",
            "PIPELINE_BUILD_NUMBER=${env.BUILD_NUMBER}",
            "PIPELINE_WORKSPACE=${env.WORKSPACE}"
          ]) {
          sh(readFile(scriptObj.script))
        }
      }
    }
  }
}

return this