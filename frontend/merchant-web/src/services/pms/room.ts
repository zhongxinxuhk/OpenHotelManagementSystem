/**
 * 房型与实体房服务（S3-01）。
 *
 * 对应后端 `RoomTypeController` 与 `RoomController`，
 * operationId：createRoomType / listRoomTypes / getRoomType / changeRoomTypeStatus /
 * createRoom / listRooms / getRoom / updateRoom / changeRoomStatus。
 */
import { request } from '@umijs/max';
import type {
  CreateRoomRequest,
  CreateRoomTypeRequest,
  RoomStatusRequest,
  RoomTypeStatusRequest,
  RoomTypeView,
  RoomView,
  UpdateRoomRequest,
} from './types';

export type {
  CreateRoomRequest,
  CreateRoomTypeRequest,
  RoomStatusRequest,
  RoomTypeStatusRequest,
  RoomTypeView,
  RoomView,
  UpdateRoomRequest,
};


// ---------- 房型 ----------

export async function listRoomTypes(hotelId: string) {
  return request<RoomTypeView[]>(`/api/v1/hotels/${hotelId}/room-types`, {
    method: 'GET',
    skipErrorHandler: true,
  });
}

export async function createRoomType(hotelId: string, body: CreateRoomTypeRequest) {
  return request<RoomTypeView>(`/api/v1/hotels/${hotelId}/room-types`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

export async function getRoomType(hotelId: string, roomTypeId: string) {
  return request<RoomTypeView>(`/api/v1/hotels/${hotelId}/room-types/${roomTypeId}`, {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 启停房型。房型下仍有 ACTIVE 房间时后端返回 409。 */
export async function changeRoomTypeStatus(
  hotelId: string,
  roomTypeId: string,
  body: RoomTypeStatusRequest,
) {
  return request<RoomTypeView>(`/api/v1/hotels/${hotelId}/room-types/${roomTypeId}/status`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

// ---------- 实体房 ----------

export interface ListRoomsParams {
  roomTypeId?: string;
  status?: string;
  floor?: number;
}

export async function listRooms(hotelId: string, params?: ListRoomsParams) {
  return request<RoomView[]>(`/api/v1/hotels/${hotelId}/rooms`, {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

export async function createRoom(hotelId: string, body: CreateRoomRequest) {
  return request<RoomView>(`/api/v1/hotels/${hotelId}/rooms`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

export async function getRoom(hotelId: string, roomId: string) {
  return request<RoomView>(`/api/v1/hotels/${hotelId}/rooms/${roomId}`, {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 修改房间属性。注意：清洁状态不在这里——它是独立字段，归房务流程。 */
export async function updateRoom(hotelId: string, roomId: string, body: UpdateRoomRequest) {
  return request<RoomView>(`/api/v1/hotels/${hotelId}/rooms/${roomId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 启停房间（运营状态，非清洁状态）。 */
export async function changeRoomStatus(hotelId: string, roomId: string, body: RoomStatusRequest) {
  return request<RoomView>(`/api/v1/hotels/${hotelId}/rooms/${roomId}/status`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}
