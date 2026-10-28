export type LeaveStatus =
  | 'SUBMITTED'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'

export interface Leave {
  id: number
  employeeName: string
  employeeEmail: string
  leaveType: string
  startDate: string
  endDate: string
  days: number
  reason?: string
  status: LeaveStatus
  statusMessage?: string
  createdAt: string
}

export interface NewLeave {
  employeeName: string
  employeeEmail: string
  leaveType: string
  startDate: string
  endDate: string
  reason: string
}

interface HttpError extends Error {
  status?: number
}

async function http<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) {
    const error: HttpError = new Error((await response.text()) || response.statusText)
    error.status = response.status
    throw error
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}

const q = encodeURIComponent

export const submitLeave = (body: NewLeave) =>
  http<Leave>('/api/leaves', {
    method: 'POST',
    body: JSON.stringify(body),
  })

export const myLeaves = (email: string) =>
  http<Leave[]>(`/api/leaves?employeeEmail=${q(email)}`)

export const pendingLeaves = () =>
  http<Leave[]>('/api/leaves/pending')

export const balance = (email: string) =>
  http<{ remainingDays: number }>(
    `/api/leaves/balance?employeeEmail=${q(email)}`
  )

// Retries HTTP 409 briefly: the Camunda task index may not have caught up with a new user task yet.
export async function decide(id: number, approved: boolean, comment: string) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await http<void>(`/api/leaves/${id}/decision`, {
        method: 'POST',
        body: JSON.stringify({ approved, comment }),
      })
    } catch (error) {
      const status = (error as HttpError).status
      if (status !== 409 || attempt >= 4) throw error
      await new Promise((resolve) => setTimeout(resolve, 1000))
    }
  }
}
