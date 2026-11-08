'use client';
import type { ComponentProps } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { ZipFolderView } from './zip-folder-view';

/** A ZIP archive browsed like a folder, each folder inside it at its own address. */
export function ZipFolder({
  href,
  onNavigate,
  ...props
}: Omit<ComponentProps<typeof ZipFolderView>, 'go' | 'crumb'> & {
  href: (path: string) => string;
}) {
  const router = useRouter();
  return (
    <ZipFolderView
      {...props}
      onNavigate={onNavigate}
      go={(path) => router.push(href(path))}
      crumb={(path, label, current) => (
        <Link
          href={href(path)}
          onNavigate={onNavigate}
          aria-current={current ? 'location' : undefined}
        >
          {label}
        </Link>
      )}
    />
  );
}
