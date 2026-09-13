import type {Metadata} from 'next';
import './globals.css';
export const metadata:Metadata={title:'Recruiser · Spatial Memory Cruiser',description:'Open GLB, PLY and Gaussian splats in a shared 6DoF scene. Add original still, video and Insta360 captures through a connected reconstruction worker.',manifest:'/manifest.webmanifest',icons:{icon:'/icons/icon-192.png',apple:'/icons/icon-192.png'}};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="en" suppressHydrationWarning><body>{children}</body></html>}
