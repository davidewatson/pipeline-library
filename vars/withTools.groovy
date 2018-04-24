def call(Map parameters = [:], body) {

  def defaultLabel = buildId('tools')
  def label = parameters.get('label', defaultLabel)
  def name = parameters.get('name', 'tools')
  def envVars = parameters.get('envVars', [])
  def inheritFrom = parameters.get('inheritFrom', 'base')
  def globalDefaults = parameters.get('defaults', [:])
  def serviceAccount = parameters.get('serviceAccount', globalDefaults.serviceAccount)
  def idleMinutes = parameters.get('idle', 10)
  def jnlpImage = parameters.get('jnlpImage', 'jenkins/jnlp-slave:3.10-1-alpine')
  def dockerImage = parameters.get('dockerImage', globalDefaults.images.docker)
  def dindImage = parameters.get('dindImage', globalDefaults.images.dind)
  def helmImage = parameters.get('helmImage', globalDefaults.images.helm)
  def vaultImage = parameters.get('vaultImage', globalDefaults.images.vault)
  def imagePullSecrets = parameters.get('imagePullSecrets', [])
  def volumes = parameters.get('volumes', [])
  def workspaceVolume = emptyDirWorkspaceVolume(memory: false)
  def containersParam = parameters.get('containers', [])
  def containerTemplates = []

  envVars.add(containerEnvVar(key: 'DOCKER_HOST', value: 'localhost:2375'))
  volumes.add(emptyDirVolume(mountPath: '/var/lib/docker', memory: false))

  containerTemplates.add(
    containerTemplate(
      name: 'jnlp', 
      image: "${jnlpImage}", 
      args: '${computer.jnlpmac} ${computer.name}'))
  containerTemplates.add(
    containerTemplate(
      name: 'dind', 
      image: "${dindImage}", 
      privileged: true,    
      alwaysPullImage: true))
  containerTemplates.add(
    containerTemplate(
      name: 'docker', 
      image: "${dockerImage}", 
      command: '/bin/sh -c', 
      args: 'cat', 
      ttyEnabled: true, 
      envVars: envVars, 
      alwaysPullImage: true))
  containerTemplates.add(
    containerTemplate(
      name: 'helm', 
      image: "${helmImage}", 
      command: '/bin/sh -c', 
      args: 'cat', 
      ttyEnabled: true, 
      envVars: envVars, 
      alwaysPullImage: true))
  containerTemplates.add(
    containerTemplate(
      name: 'vault', 
      image: "${vaultImage}",
      command: '/bin/sh -c', 
      args: 'cat', 
      ttyEnabled: true, 
      envVars: envVars, 
      alwaysPullImage: true))
  
  for (item in containersParam) {
    containerTemplates.add(
      containerTemplate(
        [
          name: item.name,
          image: item.image,
          shell: item.shell,
          command: '/bin/sh -c', 
          args: 'cat', 
          ttyEnabled: true, 
          envVars: envVars,
          alwaysPullImage: true]))
  }

  podTemplate(name: "${name}", label: label, inheritFrom: "${inheritFrom}", serviceAccount: "${serviceAccount}",
        idleMinutesStr: "${idleMinutes}",
        containers: containerTemplates,
        workspaceVolume: workspaceVolume,
        volumes: volumes,
        imagePullSecrets: imagePullSecrets,
        ) {
    body()
  }
}
