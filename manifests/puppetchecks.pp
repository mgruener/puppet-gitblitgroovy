class gitblitgroovy::puppetchecks (
  $jgitversion = '3.5.1.201410131835-r'
) {
  require gitblitgroovy

  file { "${::gitblit::datadir}/groovy/puppet-checks.groovy":
    ensure  => present,
    content => file('gitblitgroovy/gitblit-groovy/puppet-checks.groovy'),
    owner   => $::gitblit::user,
    group   => $::gitblit::group,
    mode    => '0644',
  }

  $source = "http://central.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit.archive/${jgitversion}"
  $file = "org.eclipse.jgit.archive-${jgitversion}.jar"
  staging::file { $file:
    source => "${source}/${file}",
    target => "${::gitblit::installdir}/ext/${file}",
  }
}
