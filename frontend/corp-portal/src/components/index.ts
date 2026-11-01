/**
 * 这个文件作为组件的目录
 * 目的是统一管理对外输出的组件，方便分类
 */
import Footer from './Footer';

/** 骨架期只保留布局必需组件：头像/语言/版本切换依赖身份或配置，定案后再启用。 */
export { default as ErrorBoundary } from './ErrorBoundary';
export { default as OfflineBanner } from './OfflineBanner';

export { Footer };
