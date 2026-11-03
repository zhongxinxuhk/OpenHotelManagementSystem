/**
 * 前端可达性判断 —— 企业商旅骨架（R-20261102-06）。
 *
 * DEC-17 OPEN：没有身份平面就没有授权模型。骨架期所有页面仅要求
 * 「工程能跑起来」，不设任何访问门槛——将来定案后按所选平面重写
 * （GUEST 平面的实名门槛，或独立 CORP 平面的动作模型）。
 */
export default function access(_initialState: unknown) {
  return {
    isAuthenticated: true,
  };
}
