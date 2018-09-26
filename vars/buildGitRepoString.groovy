def call(String url, String username, String password) {
  def urlComponents = url.split("://")
  urlComponents[1] = "${username}:${password}@" + components[1]
  
  return urlComponents.join("://")
}
