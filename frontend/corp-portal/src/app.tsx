import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { Link } from '@umijs/max';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';

import { ErrorBoundary, Footer, OfflineBanner } from '@/components';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';

// Initialize dayjs plugins globally
dayjs.extend(relativeTime);

/**
 * 应用入口 —— 企业商旅骨架（R-20261102-06）。
 *
 * DEC-17 OPEN：没有身份平面、没有后端接口，骨架期**不接任何会话**：
 * 无 fetchUserInfo、无登录重定向、无头像菜单（没有"当前用户"可显示）。
 * 将来定案后按所选平面重写（CORP_SSO 进 GUEST 平面，或独立 CORP 平面——
 * 新会话表与令牌域分离，参照 ADR-019 §2.2 的做法）。
 */
export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  settingDrawerOpen?: boolean;
}> {
  return {
    settings: defaultSettings as Partial<LayoutSettings>,
    settingDrawerOpen: false,
  };
}

// ProLayout 支持的api https://procomponents.ant.design/components/layout
export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  return {
    menuItemRender: (item, dom) => {
      if (item.path) {
        return (
          <Link to={item.path} prefetch>
            {dom}
          </Link>
        );
      }
      return dom;
    },
    footerRender: () => <Footer />,
    // Replace ProLayout's default ErrorBoundary with our offline-aware version.
    ErrorBoundary,
    menuHeaderRender: undefined,
    childrenRender: (children) => {
      return (
        <>
          {children}
          <SettingDrawer
            disableUrlParams
            enableDarkTheme
            collapse={initialState?.settingDrawerOpen}
            onCollapseChange={(open) => {
              setInitialState((s) => ({ ...s, settingDrawerOpen: open }));
            }}
            settings={initialState?.settings}
            onSettingChange={(settings) => {
              setInitialState((s) => ({ ...s, settings }));
            }}
          />
        </>
      );
    },
    ...initialState?.settings,
  };
};

/**
 * 请求配置：错误处理收口（骨架期无业务请求，保留统一配置以备定案后启用）。
 *
 * @doc https://umijs.org/docs/max/request#配置
 */
export const request: RequestConfig = {
  ...errorConfig,
};

export function rootContainer(container: React.ReactNode) {
  return (
    <>
      <OfflineBanner />
      <ErrorBoundary>{container}</ErrorBoundary>
    </>
  );
}
