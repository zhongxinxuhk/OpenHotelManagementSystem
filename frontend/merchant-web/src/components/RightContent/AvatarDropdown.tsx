import {
  LockOutlined,
  LogoutOutlined,
  SkinOutlined,
} from '@ant-design/icons';
import { history, useModel } from '@umijs/max';
import type { MenuProps } from 'antd';
import { Spin } from 'antd';
import React, { startTransition } from 'react';
import { authLogout } from '@/services/pms/auth';
import { clearSession } from '@/services/pms/session';
import HeaderDropdown from '../HeaderDropdown';

type GlobalHeaderRightProps = {
  children?: React.ReactNode;
};

const menuItems: MenuProps['items'] = [
  {
    key: 'password',
    icon: <LockOutlined />,
    label: '修改口令',
  },
  {
    key: 'theme',
    icon: <SkinOutlined />,
    label: '主题设置',
  },
  {
    type: 'divider' as const,
  },
  {
    key: 'logout',
    icon: <LogoutOutlined />,
    label: '退出登录',
  },
];

/**
 * 退出登录。
 *
 * 先调后端撤销当前会话（只撤当前这一条，其他设备上的会话不受影响），
 * 再清本地令牌并跳登录页。
 *
 * 顺序很重要：先撤销再跳转。反过来的话，本地令牌已清但服务端会话还在，
 * 那条会话就成了"还活着但没人知道"的僵尸会话。
 */
const loginOut = async () => {
  try {
    await authLogout();
  } catch {
    // 后端不可达时也要让用户能退出：本地清干净即可，
    // 服务端会话会在过期后失效。
  }
  clearSession();
  const { search, pathname } = window.location;
  const urlParams = new URL(window.location.href).searchParams;
  const searchParams = new URLSearchParams({
    redirect: pathname + search,
  });
  const redirect = urlParams.get('redirect');
  if (window.location.pathname !== '/user/login' && !redirect) {
    history.replace({
      pathname: '/user/login',
      search: searchParams.toString(),
    });
  }
};

export const AvatarDropdown: React.FC<GlobalHeaderRightProps> = ({
  children,
}) => {
  const { initialState, setInitialState } = useModel('@@initialState');

  const onMenuClick: MenuProps['onClick'] = (event) => {
    const { key } = event;
    if (key === 'logout') {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: undefined }));
      });
      void loginOut();
      return;
    }
    if (key === 'theme') {
      setInitialState((s) => ({ ...s, settingDrawerOpen: true }));
      return;
    }
    if (key === 'password') {
      // 改密页不在主框架里（见路由注释），这里整页跳转而不是 history.push。
      window.location.href = '/user/password';
    }
  };

  if (!initialState) {
    return <Spin size="small" />;
  }

  const { currentUser } = initialState;

  if (!currentUser) {
    return <Spin size="small" />;
  }

  return (
    <HeaderDropdown
      placement="bottomRight"
      menu={{
        selectedKeys: [],
        onClick: onMenuClick,
        items: menuItems,
      }}
      arrow
    >
      {children}
    </HeaderDropdown>
  );
};
