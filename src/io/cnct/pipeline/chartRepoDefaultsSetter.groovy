// /src/io/cnct/pipeline/defaultsSetter.groovy
package io.cnct.pipeline;

def setDefaults(rawSettings, defaults) {
  // check after and before scripts
  if (rawSettings.beforeScript) {
    rawSettings.beforeScript.image = 
      rawSettings.beforeScript.image ? rawSettings.beforeScript.image : defaults.images.script
    if (!rawSettings.beforeScript.shell) {
      rawSettings.beforeScript.shell = '/bin/sh'
    }
    if (!rawSettings.beforeScript.script) {
      rawSettings.beforeScript = null
    }
  }
  if (rawSettings.afterScript) {
    rawSettings.afterScript.image = 
      rawSettings.afterScript.image ? rawSettings.afterScript.image : defaults.images.script
    if (!rawSettings.afterScript.shell) {
      rawSettings.afterScript.shell = '/bin/sh'
    }
    if (!rawSettings.afterScript.script) {
      rawSettings.afterScript = null
    }
  }

  // check env values
  if (!rawSettings.envValues) {
    rawSettings.envValues = []
  }

  // check pull secrets
  if (!rawSettings.pullSecrets) {
    rawSettings.pullSecrets = []
  }
  for (secret in rawSettings.pullSecrets) {
    if (!secret.name) {
      error('Pull secrets must have name, server, username, password and email')
    }
    if (!secret.server) {
      error('Pull secrets must have name, server, username, password and email')
    }
    if (!secret.username) {
      error('Pull secrets must have name, server, username, password and email')
    }
    if (!secret.email) {
      error('Pull secrets must have name, server, username, password and email')
    }
    if (!secret.password) {
      error('Pull secrets must have name, server, username, password and email')
    }
  }

  // check slack settings
  if (!rawSettings.slack) {
    rawSettings.slack = [:]
  }
  if (!rawSettings.slack.channel) {
    rawSettings.slack.channel = defaults.slack.channel
  }
  rawSettings.slack.credentials = defaults.slack.credentials
  rawSettings.slack.domain = defaults.slack.domain


  // check vault settings
  if (!rawSettings.vault) {
    rawSettings.vault = [:]
  }
  if (!rawSettings.vault.server) {
    rawSettings.vault.server = defaults.vault.server
    rawSettings.vault.credentials = defaults.vault.credentials
  }
  if (!rawSettings.vault.credentials) {
    rawSettings.vault.server = defaults.vault.server
    rawSettings.vault.credentials = defaults.vault.credentials
  }
  if (!rawSettings.vault.tls) {
    rawSettings.vault.tls = [:]
  }
  if (!rawSettings.vault.tls.secret) {
    rawSettings.vault.tls = defaults.vault.tls
  }
  if (!rawSettings.vault.tls.cert) {
    rawSettings.vault.tls = defaults.vault.tls
  }
  if (!rawSettings.vault.tls.key) {
    rawSettings.vault.tls = defaults.vault.tls
  }
  if (!rawSettings.vault.tls.ca) {
    rawSettings.vault.tls = defaults.vault.tls
  }

  // check helm settings
  if (!rawSettings.helm) {
    rawSettings.helm = [:]
  }
  if (!rawSettings.helm.namespace) {
    rawSettings.helm.namespace = defaults.helm.namespace
  }

  // light checking on rootfs mappings
  if (!rawSettings.rootfs) {
    rawSettings.rootfs = []
  }

  for (entry in rawSettings.rootfs) {
    if (!entry.context) {
      error("rootfs items must have 'context' field")
    }

    if (!entry.image) {
      error("rootfs items must have 'image' field")
    }

    if (entry.test) {
      entry.test.image = 
        entry.test.image ? entry.test.image : defaults.images.script
      entry.test.shell = 
        entry.test.shell ? entry.test.shell : 'sh'

      if (!entry.test.script) {
        entry.test = null
      }
    }
  }

  // check helmConfigs
  if (!rawSettings.configs) {
    rawSettings.configs = []
  }
  for (config in rawSettings.configs) {

    if (!config.timeout) {
      config.timeout = defaults.timeout
    }

    if (!config.retries) {
      config.retries = defaults.retries
    }

    if (!config.test) {
      config.test = [:]
    }

    if (!config.test.values) {
      config.test.values = [:]
    }

    if (!config.test.tests) {
      config.test.tests = []
    }

    if (!config.stage) {
      config.stage = [:]
    }

    if (!config.stage.values) {
      config.stage.values = [:]
    }

    if (!config.stage.tests) {
      config.stage.tests = []
    }

    for (test in config.stage.tests) {
      test.image = test.image ? test.image : defaults.images.script
      test.shell = test.shell ? test.shell : defaults.shell
      if (!test.script) {
        test = null
      }
    }

    if (!config.prod) {
      config.prod = [:]
    }

    if (!config.prod.values) {
      config.prod.values = [:]
    }
  }
  
  // check test
  if (!rawSettings.test) {
    rawSettings.test = [:]
  }
  if (rawSettings.test.beforeScript) {
    rawSettings.test.beforeScript.image = 
      rawSettings.test.beforeScript.image ? rawSettings.test.beforeScript.image : defaults.images.script
    if (!rawSettings.test.beforeScript.shell) {
      rawSettings.test.beforeScript.shell = '/bin/sh'
    }
    if (!rawSettings.test.beforeScript.script) {
      rawSettings.test.beforeScript = null
    }
  }
  if (rawSettings.test.afterScript) {
    rawSettings.test.afterScript.image = 
      rawSettings.test.afterScript.image ? rawSettings.test.afterScript.image : defaults.images.script
    if (!rawSettings.test.afterScript.shell) {
      rawSettings.test.afterScript.shell = '/bin/sh'
    }
    if (!rawSettings.test.afterScript.script) {
      rawSettings.test.afterScript = null
    }
  }

  // check staging
  if (!rawSettings.stage) {
    rawSettings.stage = [:]
  }
  if (rawSettings.stage.beforeScript) {
    rawSettings.stage.beforeScript.image = 
      rawSettings.stage.beforeScript.image ? rawSettings.stage.beforeScript.image : defaults.images.script
    if (!rawSettings.stage.beforeScript.shell) {
      rawSettings.stage.beforeScript.shell = '/bin/sh'
    }
    if (!rawSettings.stage.beforeScript.script) {
      rawSettings.stage.beforeScript = null
    }
  }
  if (rawSettings.stage.afterScript) {
    rawSettings.stage.afterScript.image = 
      rawSettings.stage.afterScript.image ? rawSettings.stage.afterScript.image : defaults.images.script
    if (!rawSettings.stage.afterScript.shell) {
      rawSettings.stage.afterScript.shell = '/bin/sh'
    }
    if (!rawSettings.stage.afterScript.script) {
      rawSettings.stage.afterScript = null
    }
  }

  // check prod
  if (!rawSettings.prod) {
    rawSettings.prod = [:]
  }
  if (!rawSettings.prod.doDeploy) {
    rawSettings.prod.doDeploy = defaults.doDeploy
  }
  if (!(rawSettings.prod.doDeploy ==~ /auto|versionfile|none/)) {
    error("doDeploy must be either 'auto', 'off' or 'versionfile'")
  }

  if (rawSettings.prod.beforeScript) {
    rawSettings.prod.beforeScript.image = 
      rawSettings.prod.beforeScript.image ? rawSettings.prod.beforeScript.image : defaults.images.script
    if (!rawSettings.prod.beforeScript.script) {
      rawSettings.prod.beforeScript = null
    }
  }
  if (rawSettings.prod.afterScript) {
    rawSettings.prod.afterScript.image = 
      rawSettings.prod.afterScript.image ? rawSettings.prod.afterScript.image : defaults.images.script
    if (!rawSettings.prod.afterScript.script) {
      rawSettings.prod.afterScript = null
    }
  }

  return rawSettings
}

return this