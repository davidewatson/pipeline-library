def call(Map defaultVals) {
  echo getString(currentBuild, defaultVals)
  return isVersionFileChanged(currentBuild, defaultVals)
}

@NonCPS
def isVersionFileChanged(thisBuild, defaults) {
  def changeLogSets = thisBuild.changeSets
  
  for (changeLogSet in changeLogSets) {
    for (entry in changeLogSet.items) {      
      def files = new ArrayList(entry.affectedFiles)
      for (changedFile in files) {
        if (changedFile.path.endsWith("/${defaults.versionfile}")) {
          return true
        }
      }
    }
  }

  return false
}

@NonCPS
def getString(thisBuild, defaults) {
  def changeLogSets = thisBuild.changeSets
  def changeString += ""
  
  for (changeLogSet in changeLogSets) {
    for (entry in changeLogSet.items) {      
      def files = new ArrayList(entry.affectedFiles)
      for (changedFile in files) {
        changeString += "${entry.getMsg()}: ${changedFile.path}\n"
      }
    }
  }

  return changeString
}