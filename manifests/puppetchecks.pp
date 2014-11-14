class gitblitgroovy::puppetchecks {
  require gitblitgroovy

  file { "${::gitblit::datadir}/groovy/puppet-checks.groovy":
    ensure  => present,
    content => file('gitblitgroovy/gitblit-groovy/puppet-checks.groovy'),
    owner   => $::gitblit::user,
    group   => $::gitblit::group,
    mode    => '0644',
  }
}
