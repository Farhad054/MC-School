import { request } from './client';

export interface KolinContext {
  targetType?: 'student' | 'group';
  studentId?: string;
  currentWorkbookPage?: number;
}

export interface KolinHistoryMessage {
  role: 'user' | 'assistant';
  text: string;
}

export interface KolinResponse { type: 'text'; text: string }

const base = (classId: string) => `/online-classes/${encodeURIComponent(classId)}/kolin`;

export const kolinApi = {
  status: (classId: string) => request<{ configured: boolean }>('GET', `${base(classId)}/status`),
  send: (classId: string, text: string, history: KolinHistoryMessage[], context: KolinContext) =>
    request<KolinResponse>('POST', `${base(classId)}/messages`, {
      text,
      history: history.slice(-8).map(message => ({ role: message.role, text: message.text })),
      context: {
        ...(context.targetType ? { targetType: context.targetType } : {}),
        ...(context.studentId ? { studentId: context.studentId } : {}),
        ...(context.currentWorkbookPage !== undefined ? { currentWorkbookPage: context.currentWorkbookPage } : {}),
      },
    }),
};
