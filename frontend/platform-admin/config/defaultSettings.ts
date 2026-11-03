import type { ProLayoutProps } from '@ant-design/pro-components';

/**
 * 平台管理员控制台的布局设置（R-20261102-02）。
 *
 * 与 merchant-web 同一套 Ant Design Pro 骨架克隆而来：依赖与构建链完全一致，
 * 差异只在身份平面（PLATFORM，见 ADR-017/019）——这里登录的是平台账号，
 * 菜单按平台动作码（guest.authpolicy.* / guest.channel.*）控制可见性。
 */
const Settings: ProLayoutProps & {
  logo?: string;
} = {
  navTheme: 'light',
  colorPrimary: '#1677ff',
  layout: 'mix',
  contentWidth: 'Fluid',
  fixedHeader: false,
  fixSiderbar: true,
  colorWeak: false,
  title: 'PMS 平台管理',
  logo: '/logo.svg',
  iconfontUrl: '',
  token: {},
};

export default Settings;
