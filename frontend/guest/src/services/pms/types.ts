/**
 * 由 OpenAPI 契约生成的类型 —— 不要手改。
 *
 * 契约来源：docs/api/openapi-v1.json（后端 /v3/api-docs 导出，由 OpenApiContractTest 守护）。
 * 重新生成：node scripts/gen-pms-types.mjs
 *
 * 字段可选性来自契约的 required 列表：必填字段在 TS 里是必填的，
 * 可选字段带 ? —— 与后端的校验规则一致，避免前端以为可以不传。
 *
 * 每个 schema 单独导出成具名 type（而不是塞进一个大 interface）：
 * 前者可以被别的类型互相引用，后者在 interface 内部无法自引用。
 */

export type AvailabilityView = {
  bookable?: BookableRoomType[];
  days?: InventoryDay[];
  from?: string;
  nights?: number;
  to?: string;
};

export type BindUsernameRequest = {
  password: string;
  username: string;
};

export type BookableRoomType = {
  bookableRooms?: number;
  roomTypeId?: string;
};

export type ChallengeRequest = {
  provider: string;
  subject: string;
};

export type ChallengeView = {
  challengeId?: string;
  expiresAt?: string;
  provider?: string;
  subjectMasked?: string;
};

export type ChangePasswordRequest = {
  currentPassword: string;
  newPassword: string;
};

export type ChannelView = {
  circuitState?: string;
  code?: string;
  consecutiveFailures?: number;
  credentialRef?: string;
  endpoint?: string;
  implementationAvailable?: boolean;
  kind?: string;
  lastFailureReason?: string;
  name?: string;
  openedUntil?: string;
  priority?: number;
  status?: string;
  timeoutMs?: number;
};

export type CodeLoginChallengeRequest = {
  email: string;
};

export type CodeLoginChallengeView = {
  challengeId?: string;
  expiresAt?: string;
  subjectMasked?: string;
};

export type CodeLoginRequest = {
  challengeId: string;
  code: string;
};

export type ConfirmRequest = {
  challengeId: string;
  code: string;
};

export type CreateChannelRequest = {
  code: string;
  credentialRef?: string;
  endpoint?: string;
  kind: string;
  name: string;
  priority?: number;
  timeoutMs?: number;
};

export type CreateReservationRequest = {
  idempotencyKey: string;
  lines: LineRequest[];
  sourceType: string;
};

export type CreateRoleRequest = {
  code: string;
  name: string;
  permissionCodes?: string[];
};

export type CreateRoomRequest = {
  code: string;
  floor?: number;
  notes?: string;
  roomTypeId: string;
};

export type CreateRoomTypeRequest = {
  bedType?: string;
  capacityAdults?: number;
  capacityChildren?: number;
  code: string;
  name: string;
};

export type CreateUserRequest = {
  displayName: string;
  hotelIds?: string[];
  roleIds: string[];
  username: string;
};

export type CreatedUserView = {
  hotelIds?: string[];
  initialPassword?: string;
  roles?: RoleBriefView[];
  user?: IdentityUserView;
};

export type DeadLetter = {
  deadLetterId?: string;
  error?: string;
  messageBody?: string;
  receivedAt?: string;
  source?: string;
  status?: string;
  tenantId?: string;
};

export type DetailLineView = {
  assignedRoomCode?: string;
  assignedRoomId?: string;
  lineId?: string;
  lineNo?: number;
  occupancyStatus?: string;
  plannedRange?: StayRange;
  requestedRoomTypeId?: string;
  status?: string;
};

export type Difference = {
  detail?: string;
  kind?: string;
  roomId?: string;
  stayDate?: string;
};

export type ExternalTask = {
  attempts?: number;
  channel?: string;
  hotelId?: string;
  idempotencyKey?: string;
  lastError?: string;
  leaseToken?: string;
  maxAttempts?: number;
  nextAttemptAt?: string;
  payload?: string;
  resultRef?: string;
  status?: string;
  taskId?: string;
  taskType?: string;
  tenantId?: string;
};

export type FindingView = {
  detail?: string;
  findingId?: string;
  firstSeenAt?: string;
  hotelId?: string;
  kind?: string;
  lastSeenAt?: string;
  occurrences?: number;
  repairedAt?: string;
  reservationId?: string;
  roomId?: string;
  settleReason?: string;
  settledAt?: string;
  settledBy?: string;
  severity?: string;
  status?: string;
  stayDate?: string;
  targetKey?: string;
  tenantId?: string;
};

export type GuestAuthResponse = {
  expiresAt?: string;
  token?: string;
  user?: GuestMeView;
};

export type GuestCredentialView = {
  provider?: string;
  subject?: string;
  verifiedAt?: string;
};

export type GuestLoginRequest = {
  password: string;
  username: string;
};

export type GuestMeView = {
  displayName?: string;
  realNameRequired?: boolean;
  sessionExpiresAt?: string;
  verificationStatus?: string;
};

export type GuestMethodView = {
  available?: boolean;
  enabled?: boolean;
  implemented?: boolean;
  provider?: string;
  updatedAt?: string;
  updatedBy?: string;
};

export type GuestRegisterRequest = {
  displayName?: string;
  password: string;
  username: string;
};

export type HotelScopesRequest = {
  hotelIds: string[];
};

export type HotelScopesView = {
  hotelIds?: string[];
  userId?: string;
};

export type HotelView = {
  code?: string;
  id?: string;
  name?: string;
  status?: string;
  timezone?: string;
};

export type IdentityUserView = {
  createdAt?: string;
  displayName?: string;
  id?: string;
  lastLoginAt?: string;
  status?: string;
  username?: string;
  version?: number;
};

export type InventoryDay = {
  availableRooms?: number;
  blockedRooms?: number;
  roomTypeId?: string;
  soldRooms?: number;
  stayDate?: string;
  totalRooms?: number;
};

export type LifecycleRequest = {
  expectedVersion?: number;
  idempotencyKey: string;
};

export type LineRequest = {
  checkIn: string;
  checkOut: string;
  roomTypeId: string;
};

export type LineView = {
  assignedRoomId?: string;
  lineId?: string;
  occupancyId?: string;
  requestedRoomTypeId?: string;
  stayRange?: StayRange;
};

export type LoginRequest = {
  password: string;
  tenantCode: string;
  username: string;
};

export type LoginResponse = {
  expiresAt?: string;
  token?: string;
  user?: UserView;
};

export type OperationView = {
  commandName?: string;
  completedAt?: string;
  createdAt?: string;
  hotelId?: string;
  operationId?: string;
  resultCode?: string;
  resultRef?: string;
  status?: string;
};

export type OpsIntervention = {
  action?: string;
  afterState?: string;
  beforeState?: string;
  interventionId?: string;
  occurredAt?: string;
  operatorId?: string;
  operatorName?: string;
  reason?: string;
  reviewerName?: string;
  targetId?: string;
  targetType?: string;
  tenantId?: string;
};

export type PageViewIdentityUserView = {
  items?: IdentityUserView[];
  page?: number;
  size?: number;
  total?: number;
  totalPages?: number;
};

export type PageViewPendingVerification = {
  items?: PendingVerification[];
  page?: number;
  size?: number;
  total?: number;
  totalPages?: number;
};

export type PageViewReservationSummary = {
  items?: ReservationSummary[];
  page?: number;
  size?: number;
  total?: number;
  totalPages?: number;
};

export type PasswordChangeRequest = {
  currentPassword: string;
  newPassword: string;
};

export type PasswordResetRequest = {
  challengeId: string;
  code: string;
  newPassword: string;
};

export type PendingSummary = {
  deadLetters?: number;
  parkedInbox?: number;
  stuckTasks?: number;
};

export type PendingVerification = {
  accountId?: string;
  idNumberMask?: string;
  realNameMask?: string;
  status?: string;
  submittedAt?: string;
};

export type Permission = {
  code?: string;
  description?: string;
  name?: string;
};

export type PlatformChangePasswordRequest = {
  currentPassword: string;
  newPassword: string;
};

export type PlatformLoginRequest = {
  password: string;
  username: string;
};

export type PlatformLoginResponse = {
  expiresAt?: string;
  token?: string;
  user?: PlatformPrincipalView;
};

export type PlatformPrincipalView = {
  actions?: string[];
  displayName?: string;
  mustChangePassword?: boolean;
  sessionExpiresAt?: string;
  username?: string;
};

export type PolicyUpdateRequest = {
  enabled: boolean;
};

export type PolicyView = {
  available?: boolean;
  enabled?: boolean;
  implemented?: boolean;
  provider?: string;
  updatedAt?: string;
  updatedBy?: string;
};

export type PublicCatalogView = {
  from?: string;
  hotel?: PublicHotelView;
  nights?: number;
  roomTypes?: PublicRoomTypeView[];
  tenantCode?: string;
  to?: string;
};

export type PublicHotelView = {
  code?: string;
  name?: string;
  timezone?: string;
};

export type PublicRoomTypeView = {
  bedType?: string;
  bookable?: boolean;
  capacityAdults?: number;
  capacityChildren?: number;
  code?: string;
  name?: string;
};

export type RebuildResult = {
  deletedRows?: number;
  from?: string;
  to?: string;
  writtenRows?: number;
};

export type ReconcileReport = {
  differences?: Difference[];
  expectedRows?: number;
  from?: string;
  hotelId?: string;
  ledgerMismatches?: number;
  missingRows?: number;
  orphanRows?: number;
  staleRows?: number;
  storedRows?: number;
  to?: string;
  truncated?: boolean;
};

export type ReconcileRunReport = {
  hotelId?: string;
  newFindings?: number;
  openBacklog?: number;
  openDerived?: number;
  openFact?: number;
  openFindings?: number;
  repairedFindings?: number;
  repeatedFindings?: number;
  runId?: string;
  supersededFindings?: number;
  triggerSource?: string;
  windowFrom?: string;
  windowTo?: string;
};

export type ReconcileRunView = {
  error?: string;
  finishedAt?: string;
  hotelId?: string;
  newFindings?: number;
  openFindings?: number;
  repairedFindings?: number;
  repeatedFindings?: number;
  runId?: string;
  startedAt?: string;
  status?: string;
  supersededFindings?: number;
  triggerSource?: string;
  windowFrom?: string;
  windowTo?: string;
};

export type RepairResult = {
  applied?: boolean;
  detail?: string;
  findingId?: string;
};

export type ReplayResult = {
  applied?: boolean;
  detail?: string;
  notFound?: boolean;
};

export type ReservationDetailView = {
  allowedActions?: string[];
  createdAt?: string;
  id?: string;
  lines?: DetailLineView[];
  reservationNo?: string;
  sourceType?: string;
  status?: string;
  stayRange?: StayRange;
  updatedAt?: string;
  version?: number;
};

export type ReservationSummary = {
  checkIn?: string;
  checkOut?: string;
  createdAt?: string;
  id?: string;
  lineCount?: number;
  reservationNo?: string;
  sourceType?: string;
  status?: string;
  updatedAt?: string;
  version?: number;
};

export type ReservationView = {
  id?: string;
  lines?: LineView[];
  reservationNo?: string;
  sourceType?: string;
  status?: string;
};

export type ResetChallengeRequest = {
  email: string;
};

export type ResetChallengeView = {
  challengeId?: string;
  expiresAt?: string;
  subjectMasked?: string;
};

export type ResetCircuitRequest = {
  reason: string;
};

export type ReviewDecisionRequest = {
  decision: string;
  reason: string;
};

export type ReviewView = {
  accountId?: string;
  decidedAt?: string;
  decision?: string;
  idNumberMask?: string;
  realNameMask?: string;
  reason?: string;
  reviewerName?: string;
  source?: string;
};

export type RoleBriefView = {
  code?: string;
  id?: string;
  name?: string;
  status?: string;
};

export type RoleView = {
  code?: string;
  id?: string;
  name?: string;
  permissionCodes?: string[];
  status?: string;
  version?: number;
};

export type RoomStatusRequest = {
  status: string;
};

export type RoomTypeStatusRequest = {
  status: string;
};

export type RoomTypeView = {
  bedType?: string;
  capacityAdults?: number;
  capacityChildren?: number;
  code?: string;
  id?: string;
  name?: string;
  status?: string;
  version?: number;
};

export type RoomView = {
  code?: string;
  floor?: number;
  housekeepingStatus?: string;
  id?: string;
  notes?: string;
  roomTypeId?: string;
  status?: string;
  version?: number;
};

export type StatusRequest = {
  status: string;
};

export type StayRange = {
  checkIn?: string;
  checkOut?: string;
};

export type SummaryView = {
  lastRun?: ReconcileRunView;
  needsAttention?: boolean;
  openBacklog?: number;
  openDerived?: number;
  openFact?: number;
  openTotal?: number;
  scannedThrough?: string;
};

export type UpdateChannelRequest = {
  credentialRef?: string;
  endpoint?: string;
  name: string;
  priority?: number;
  status: string;
  timeoutMs?: number;
};

export type UpdateRoomRequest = {
  floor?: number;
  notes?: string;
  roomTypeId?: string;
};

export type UserDetailView = {
  hotelIds?: string[];
  roles?: RoleBriefView[];
  user?: IdentityUserView;
};

export type UserRolesRequest = {
  roleIds: string[];
};

export type UserRolesView = {
  roles?: RoleBriefView[];
  userId?: string;
};

export type UserView = {
  displayName?: string;
  hotelIds?: string[];
  mustChangePassword?: boolean;
  permissions?: string[];
  sessionExpiresAt?: string;
  tenantId?: string;
  userId?: string;
  username?: string;
};
