import axios from 'axios';
import { createClient } from '@/lib/supabase/client';

export const api = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL,
});

api.interceptors.request.use(async (config) => {
  const supabase = createClient();
  const {
    data: { session },
  } = await supabase.auth.getSession();

  console.log(
    `[api] ${config.method?.toUpperCase()} ${config.url} — session present: ${!!session}, token present: ${!!session?.access_token}`
  );

  if (session?.access_token) {
    config.headers.Authorization = `Bearer ${session.access_token}`;
  }

  return config;
});
