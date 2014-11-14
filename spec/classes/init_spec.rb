require 'spec_helper'
describe 'gitblitgroovy' do

  context 'with defaults for all parameters' do
    it { should contain_class('gitblitgroovy') }
  end
end
