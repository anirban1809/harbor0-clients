'use client';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { LayoutPanelTop } from 'lucide-react';
import type { Transport } from '@harbor/api-client';
import type { PublicLink } from '@harbor/contracts';
import { fileDate } from '../lib/file-metadata';
import { ClientRoomDialog } from './client-room-dialog';
import { EmptyState, LoadError } from './empty-state';
import { ContentSkeleton } from './loading-states';
import { Button } from './ui/button';
import { ActionsMenu, MenuItem } from './ui/menu';
import { DataTable } from './ui/table';

/** The Data rooms page: every client room, to change or check who's been there. */
export function ClientRoomsList({
  request,
  onBrowse,
}: {
  request: Transport;
  onBrowse: () => void;
}) {
  const queries = useQueryClient();
  const links = useQuery<PublicLink[]>({
    queryKey: ['public-links'],
    queryFn: async () => (await request('/v1/links')).items,
  });
  const [managing, setManaging] = useState<PublicLink>();
  if (links.isPending) return <ContentSkeleton label="Loading data rooms" />;
  if (links.isError && !links.data) return <LoadError onRetry={() => void links.refetch()} />;
  const rooms = links.data.filter((link) => link.room);
  if (!rooms.length)
    return (
      <EmptyState
        icon={<LayoutPanelTop />}
        title="No data rooms"
        description="A data room is a page for a folder where people can download files and upload their own, with no account. Choose a folder in My Drive and select “Data room” from its menu."
        actions={
          <Button variant="outline" onClick={onBrowse}>
            Browse My Drive
          </Button>
        }
      />
    );
  return (
    <>
      <DataTable className="files-table" label="Data rooms">
        <caption className="sr-only">Your data rooms with when each ends and was made.</caption>
        <thead>
          <tr>
            <th scope="col" className="file-name-column">
              <div className="file-heading-name">
                <span>Name</span>
              </div>
            </th>
            <th scope="col" className="file-size-column">
              Ends
            </th>
            <th scope="col" className="file-modified-column">
              Created
            </th>
            <th scope="col" className="file-actions-column">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rooms.map((link) => {
            const name = link.itemName ?? 'Folder no longer available';
            const made = fileDate(link.createdAt);
            const ends = link.expiresAt ? fileDate(link.expiresAt).date : 'Never';
            return (
              <tr className="file-entry-row" key={link.id}>
                <td>
                  <div className="file-entry-identity">
                    <button
                      type="button"
                      className="file-entry-open"
                      disabled={!link.itemName}
                      onClick={() => setManaging(link)}
                    >
                      <span className="file-entry-icon" data-kind="folder">
                        <LayoutPanelTop aria-hidden="true" />
                      </span>
                      <span className="file-entry-label">
                        <strong>{name}</strong>
                        <span className="file-entry-mobile-meta" aria-hidden="true">
                          {ends === 'Never' ? 'Never ends' : `Ends ${ends}`}
                        </span>
                      </span>
                    </button>
                  </div>
                </td>
                <td className="file-size-column">{ends}</td>
                <td className="file-modified-column">
                  <span className="file-date-detail" title={made.full}>
                    {made.date}
                  </span>
                </td>
                <td className="file-actions-column">
                  <ActionsMenu label={`Actions for ${name}`}>
                    <MenuItem disabled={!link.itemName} onClick={() => setManaging(link)}>
                      Manage data room
                    </MenuItem>
                  </ActionsMenu>
                </td>
              </tr>
            );
          })}
        </tbody>
      </DataTable>
      {managing && (
        <ClientRoomDialog
          request={request}
          folder={{ id: managing.driveItemId, name: managing.itemName ?? '' }}
          onClose={() => {
            setManaging(undefined);
            void queries.invalidateQueries({ queryKey: ['public-links'] });
          }}
        />
      )}
    </>
  );
}
