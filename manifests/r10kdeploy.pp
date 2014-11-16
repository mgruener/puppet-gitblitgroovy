class gitblitgroovy::r10kdeploy (
  $use_sudo = true,
  $runas    = pick($::r10k::config::cachedirgroup,'puppet'),
) {
  require gitblitgroovy

  if $use_sudo {
    sudo::sudoers { 'r10kdeploy':
      ensure  => 'present',
      comment => 'Allow r10k to be called by gitblit',
      users   => ['gitblit'],
      runas    => [$runas],
      cmnds    => ['/bin/r10k'],
      tags     => ['NOPASSWD'],
      defaults => ['!requiretty'],
    }
  }

  file { "${::gitblit::datadir}/groovy/r10k-deploy.groovy":
    ensure  => present,
    content => file('gitblitgroovy/gitblit-groovy/r10k-deploy.groovy'),
    owner   => $::gitblit::user,
    group   => $::gitblit::group,
    mode    => '0644',
    require => Staging::File[$file],
  }
}
