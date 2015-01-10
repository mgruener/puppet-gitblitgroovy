class gitblitgroovy::puppetchecks (
  $jgitversion = '3.5.1.201410131835-r'
) {
  require gitblitgroovy

  $source = "http://central.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit.archive/${jgitversion}"
  $file = "org.eclipse.jgit.archive-${jgitversion}.jar"
  staging::file { $file:
    source => "${source}/${file}",
    target => "${::gitblit::installdir}/ext/${file}",
  }

  file { "${::gitblit::datadir}/groovy/puppet-checks.groovy":
    ensure  => present,
    content => file('gitblitgroovy/gitblit-groovy/puppet-checks.groovy'),
    owner   => $::gitblit::user,
    group   => $::gitblit::group,
    mode    => '0644',
    require => Staging::File[$file],
  }

  gitblit::config { 'puppetchecks.checks':
    ensure => present,
    value  => 'validate lint erb yaml json',
  }

  gitblit::config { 'puppetchecks.lintoptions':
    ensure => present,
    value  => 'puppetchecks=Puppet checks (overrides puppetchecks.checks)" "puppetcheckslintoptions=Puppet Lint Options (overrides puppetchecks.lintoptions)',
  }
}
