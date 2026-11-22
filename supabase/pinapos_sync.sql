-- =====================================================================
-- PinaPos - sinkron Supabase (jalankan SEKALI di Supabase -> SQL Editor)
--
-- Cara kerja: tiap perubahan data dari perangkat kasir disimpan sebagai
-- "event" berurutan (append-only). Perangkat lain menarik event yang
-- belum mereka punya. Aplikasi hanya memanggil 3 fungsi di bawah dengan
-- anon/publishable key; tabelnya sendiri TERKUNCI (tidak bisa dibaca
-- langsung lewat API). Kunci akses = "kode toko" acak (32 karakter) yang
-- dibuat aplikasi saat Anda menekan "Buat toko baru".
-- Aman dijalankan ulang.
-- =====================================================================

create table if not exists public.pinapos_changes (
  seq        bigint      primary key,
  store_id   text        not null,
  device_id  text        not null,
  ref        text        not null,
  tbl        text        not null,
  op         text        not null,
  row_id     bigint,
  data       jsonb       not null,
  created_at timestamptz not null default now(),
  unique (store_id, ref)                       -- kirim ulang tidak menggandakan
);
create index if not exists pinapos_changes_store_seq on public.pinapos_changes (store_id, seq);

create table if not exists public.pinapos_devices (
  store_id   text        not null,
  device_id  text        not null,
  tag        text        not null,             -- huruf perangkat (A, B, C...) di nomor invoice
  label      text,
  created_at timestamptz not null default now(),
  primary key (store_id, device_id),
  unique (store_id, tag)
);

create sequence if not exists public.pinapos_seq;

-- Kunci tabel: tanpa policy = anon/authenticated tidak bisa akses langsung.
alter table public.pinapos_changes enable row level security;
alter table public.pinapos_devices enable row level security;
revoke all on public.pinapos_changes from anon, authenticated;
revoke all on public.pinapos_devices from anon, authenticated;
revoke all on sequence public.pinapos_seq from anon, authenticated;

-- ---------------------------------------------------------------------
-- Daftarkan perangkat; mengembalikan huruf perangkat (A..Z).
-- p_create = true  -> boleh membuat toko baru
-- p_create = false -> hanya bergabung ke toko yang sudah ada
-- ---------------------------------------------------------------------
create or replace function public.pinapos_register(
  p_store text, p_device text, p_label text default null, p_create boolean default false
) returns text
language plpgsql security definer set search_path = public as $$
declare
  t text; i int; cnt int;
  letters constant text := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
begin
  if p_store is null or length(p_store) < 24 then raise exception 'kode toko tidak valid'; end if;
  if p_device is null or length(p_device) < 4 then raise exception 'id perangkat tidak valid'; end if;
  perform pg_advisory_xact_lock(hashtextextended('pinapos:' || p_store, 0));

  select tag into t from pinapos_devices where store_id = p_store and device_id = p_device;
  if t is not null then return t; end if;

  select count(*) into cnt from pinapos_devices where store_id = p_store;
  if cnt = 0 and not p_create then raise exception 'KODE_TOKO_TIDAK_DITEMUKAN'; end if;
  if cnt >= 26 then raise exception 'jumlah perangkat maksimal 26 per toko'; end if;

  for i in 1..26 loop
    t := substr(letters, i, 1);
    exit when not exists (select 1 from pinapos_devices where store_id = p_store and tag = t);
  end loop;
  insert into pinapos_devices (store_id, device_id, tag, label)
  values (p_store, p_device, t, left(coalesce(p_label, ''), 80));
  return t;
end $$;

-- ---------------------------------------------------------------------
-- Kirim event. Penulisan per toko diserialkan (advisory lock) supaya urutan
-- "seq" sama dengan urutan commit -> perangkat lain tidak pernah kelewatan event.
-- ---------------------------------------------------------------------
create or replace function public.pinapos_push(p_store text, p_device text, p_events jsonb)
returns bigint
language plpgsql security definer set search_path = public as $$
declare e jsonb;
begin
  if p_store is null or length(p_store) < 24 then raise exception 'kode toko tidak valid'; end if;
  if not exists (select 1 from pinapos_devices where store_id = p_store and device_id = p_device) then
    raise exception 'KODE_TOKO_TIDAK_DITEMUKAN';
  end if;
  if jsonb_typeof(p_events) <> 'array' or jsonb_array_length(p_events) > 1000 then
    raise exception 'format event tidak valid';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('pinapos:' || p_store, 0));
  for e in select * from jsonb_array_elements(p_events) loop
    insert into pinapos_changes (seq, store_id, device_id, ref, tbl, op, row_id, data)
    values (nextval('public.pinapos_seq'), p_store, p_device,
            e->>'ref', coalesce(e->>'t', ''), coalesce(e->>'o', ''), nullif(e->>'id', '')::bigint, e)
    on conflict (store_id, ref) do nothing;
  end loop;
  return coalesce((select max(seq) from pinapos_changes where store_id = p_store), 0);
end $$;

-- ---------------------------------------------------------------------
-- Tarik event yang belum dimiliki perangkat (seq > p_after), urut.
-- ---------------------------------------------------------------------
create or replace function public.pinapos_pull(p_store text, p_after bigint default 0, p_limit int default 500)
returns table (seq bigint, device_id text, data jsonb)
language plpgsql security definer set search_path = public stable as $$
begin
  if p_store is null or length(p_store) < 24 then raise exception 'kode toko tidak valid'; end if;
  return query
    select c.seq, c.device_id, c.data
    from pinapos_changes c
    where c.store_id = p_store and c.seq > coalesce(p_after, 0)
    order by c.seq
    limit least(greatest(coalesce(p_limit, 500), 1), 1000);
end $$;

-- Hanya 3 fungsi ini yang boleh dipanggil dari aplikasi.
revoke all on function public.pinapos_register(text, text, text, boolean) from public;
revoke all on function public.pinapos_push(text, text, jsonb) from public;
revoke all on function public.pinapos_pull(text, bigint, int) from public;
grant execute on function public.pinapos_register(text, text, text, boolean) to anon, authenticated;
grant execute on function public.pinapos_push(text, text, jsonb) to anon, authenticated;
grant execute on function public.pinapos_pull(text, bigint, int) to anon, authenticated;

notify pgrst, 'reload schema';
