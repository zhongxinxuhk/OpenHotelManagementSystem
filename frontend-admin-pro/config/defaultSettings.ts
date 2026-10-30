import type { ProLayoutProps } from '@ant-design/pro-components';

/**
 * @name
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
  // 站点标题与图标：模板默认是 "Ant Design Pro" + 支付宝 CDN 上的 antd logo。
  // 标题直接决定浏览器标题与 ProLayout 品牌区，留着模板值等于对外宣称这是模板；
  // logo 改成 public/ 下的本地副本，避免构建产物依赖外部 CDN（断网/被墙就白块）。
  title: '酒店 PMS',
  logo: '/logo.svg',
  iconfontUrl: '',
  token: {
    // 参见ts声明，demo 见文档，通过token 修改样式
    //https://procomponents.ant.design/components/layout#%E9%80%9A%E8%BF%87-token-%E4%BF%AE%E6%94%B9%E6%A0%B7%E5%BC%8F
  },
};

export default Settings;
