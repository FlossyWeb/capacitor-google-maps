require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'FlossywebCapacitorGoogleMaps'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = 'https://capacitorjs.com'
  s.author = package['author']
  s.source = { :git => 'https://github.com/ionic-team/capacitor-plugins.git', :tag => package['name'] + '@' + package['version'] }
  s.source_files = 'ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}', 'google-maps/ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target  = '14.0'
  s.dependency 'Capacitor'
  s.dependency 'GoogleMaps', '~> 8.4'
  s.dependency 'Google-Maps-iOS-Utils', '~> 5.0'
  s.dependency 'SDWebImage', '~> 5.0'
  s.swift_version = '5.1'
  s.static_framework = true
end
