def call(Map defaultVals) {
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